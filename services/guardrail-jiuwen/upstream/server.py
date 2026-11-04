"""
智能体安全防护服务的FastAPI服务器（不依赖 LangChain）

基于 HardenedEngine 提供安全检测服务，通过 /v1/rails/input 和 /v1/rails/output 端点
供 JiuwenSwarm Rail 扩展调用。
"""
import json
import logging
import re
import sys
import os
from typing import List, Dict, Any, Optional
from dataclasses import dataclass
import uuid
from datetime import datetime, timedelta
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, BackgroundTasks, Request, Depends
from fastapi.responses import StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, field_validator
from sqlalchemy.ext.asyncio import AsyncSession

from config.config import (
    setup_logging, SERVER_CONFIG, BASE_DIR, GUARDRAILS_DEFAULT_RULES,
    PII_ENTITY_OPTIONS, SECURITY_CHECK_PROMPT,
    get_guardrail_config, update_guardrail_config,
)

# 添加 adapters 目录到 sys.path，以便导入 HardenedEngine
_adapters_dir = str(BASE_DIR.parent / "adapters")
if _adapters_dir not in sys.path:
    sys.path.insert(0, _adapters_dir)

from guardrail_runtime.hardened_engine import HardenedEngine, DetectionResult, DECISION_BLOCK, DECISION_ALLOW, DECISION_UNAVAILABLE
from guardrail_runtime.policy_loader import PolicyLoader
from guardrail_runtime.llm_adapter import LLMAdapter

from src.pii_detection import get_pii_service
from database import (
    init_db, close_db, get_db,
    RequestLogCRUD, GuardrailRuleCRUD, PIIConfigCRUD,
    PromptDefenseConfigCRUD, WhitelistRuleCRUD,
    ContentFilterConfigCRUD, ContentFilterCategoryCRUD,
    ContentFilterAnchorCRUD
)
from database.connection import async_session_factory
from src.classifier.categories import DEFAULT_THRESHOLDS, CATEGORY_ANCHORS
from src.classifier.service import get_classifier_service

# 设置日志
setup_logging()
logger = logging.getLogger("agent_service")
security_logger = logging.getLogger("security")


# 应用生命周期管理
@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理，处理启动和关闭事件"""
    global engine, policy_loader

    # 启动时初始化数据库
    logger.info("正在初始化数据库...")
    await init_db()
    logger.info("数据库初始化完成")

    # 初始化系统默认规则
    logger.info("正在初始化系统默认规则...")
    async for db in get_db():
        try:
            inserted_count = await GuardrailRuleCRUD.initialize_system_rules(db, GUARDRAILS_DEFAULT_RULES)
            await db.commit()  # 提交事务
            if inserted_count > 0:
                logger.info(f"已插入 {inserted_count} 条系统默认规则")
            else:
                logger.info("系统默认规则已存在，跳过初始化")
        except Exception as e:
            await db.rollback()  # 回滚事务
            logger.error(f"初始化系统规则失败: {str(e)}")
            raise
        break

    # 初始化PII配置（从数据库加载，默认禁用）
    logger.info("正在初始化PII配置...")
    pii_config_obj = None
    async for db in get_db():
        try:
            pii_config_obj = await PIIConfigCRUD.get_or_create_config(db)
            await db.commit()
            logger.info(f"PII配置加载完成: input_enabled={pii_config_obj.input_enabled}, output_enabled={pii_config_obj.output_enabled}")
            break
        except Exception as e:
            await db.rollback()
            logger.error(f"PII配置初始化失败: {str(e)}", exc_info=True)
            raise

    # 初始化提示词防御配置（从数据库加载，首次启动时从 config.py 写入默认值）
    logger.info("正在初始化提示词防御配置...")
    prompt_config_obj = None
    async for db in get_db():
        try:
            prompt_config_obj = await PromptDefenseConfigCRUD.get_or_create_config(
                db, default_prompt=SECURITY_CHECK_PROMPT
            )
            await db.commit()
            logger.info(f"提示词防御配置加载完成: enabled={prompt_config_obj.enabled}")
            break
        except Exception as e:
            await db.rollback()
            logger.error(f"提示词防御配置初始化失败: {str(e)}", exc_info=True)
            raise

    # 初始化内容过滤配置（从数据库加载，首次启动时使用默认值）
    logger.info("正在初始化内容过滤配置...")
    cf_config_obj = None
    async for db in get_db():
        try:
            cf_config_obj = await ContentFilterConfigCRUD.get_or_create_config(db)
            await db.commit()
            logger.info(
                f"内容过滤配置加载完成: input_enabled={cf_config_obj.input_enabled}, "
                f"output_enabled={cf_config_obj.output_enabled}, "
                f"action_mode={cf_config_obj.action_mode}"
            )
            break
        except Exception as e:
            await db.rollback()
            logger.error(f"内容过滤配置初始化失败: {str(e)}", exc_info=True)
            raise

    # 初始化内容过滤类别和锚点（首次启动时从 categories.py 加载到数据库）
    logger.info("正在初始化内容过滤类别和锚点...")
    async for db in get_db():
        try:
            # 准备默认类别数据（含阈值）
            default_categories = {
                name: {"threshold": DEFAULT_THRESHOLDS.get(name, 0.75), "description": f"系统默认类别: {name}"}
                for name in DEFAULT_THRESHOLDS.keys()
            }

            # 初始化系统类别
            cat_count = await ContentFilterCategoryCRUD.initialize_system_categories(db, default_categories)
            if cat_count > 0:
                logger.info(f"已创建 {cat_count} 个默认内容过滤类别")

            # 初始化系统锚点
            anchor_count = await ContentFilterAnchorCRUD.initialize_system_anchors(db, CATEGORY_ANCHORS)
            if anchor_count > 0:
                logger.info(f"已创建 {anchor_count} 个默认内容过滤锚点")

            await db.commit()
            break
        except Exception as e:
            await db.rollback()
            logger.error(f"内容过滤类别/锚点初始化失败: {str(e)}", exc_info=True)
            raise

    # 向量化缺少 embedding 的锚点
    logger.info("正在检查并向量化锚点...")
    async for db in get_db():
        try:
            cf_service = get_classifier_service()
            vectorized = await cf_service.vectorize_missing_anchors(db)
            if vectorized > 0:
                logger.info(f"已向量化 {vectorized} 个锚点")
            await db.commit()
            break
        except Exception as e:
            await db.rollback()
            logger.error(f"锚点向量化失败: {str(e)}", exc_info=True)
            raise

    # 从数据库加载内容过滤数据到内存
    logger.info("正在从数据库加载内容过滤数据到内存...")
    async for db in get_db():
        try:
            cf_service = get_classifier_service()
            loaded = await cf_service.load_from_db(db)
            if loaded:
                logger.info("内容过滤数据已加载到内存")
            else:
                logger.warning("内容过滤数据加载失败，内容过滤功能可能不可用")
            break
        except Exception as e:
            logger.error(f"加载内容过滤数据到内存失败: {str(e)}", exc_info=True)
            raise

    # 初始化白名单规则（从数据库加载）
    logger.info("正在初始化白名单规则...")
    async for db in get_db():
        try:
            whitelist_rules = await WhitelistRuleCRUD.get_all_rules(db, active_only=True)
            whitelist_patterns = [rule.pattern for rule in whitelist_rules]
            logger.info(f"白名单规则加载完成: 共 {len(whitelist_patterns)} 条规则")
            break
        except Exception as e:
            logger.error(f"白名单规则初始化失败: {str(e)}", exc_info=True)
            raise

    # ============ 初始化 HardenedEngine（替代 LangChainAgent）============
    logger.info("正在初始化 HardenedEngine（不依赖 LangChain）...")
    try:
        # 策略文件目录：项目根目录下的 policy/
        policy_dir = BASE_DIR.parent / "policy"
        if not policy_dir.exists():
            # fallback: upstream 目录下的 policy/
            policy_dir = BASE_DIR / "policy"
        policy_loader = PolicyLoader(policy_dir=str(policy_dir))
        llm_adapter = LLMAdapter()
        # enable_* 标志传 True，让 HardenedEngine 从 JSON 策略文件读取实际开关
        engine = HardenedEngine(
            policy_loader=policy_loader,
            llm_adapter=llm_adapter,
            enable_pii=True,
            enable_content_filter=True,
            enable_llm_defense=True,
            enable_whitelist=True,
        )
        logger.info("HardenedEngine 初始化成功")
    except Exception as e:
        logger.error(f"HardenedEngine 初始化失败: {str(e)}", exc_info=True)
        raise

    logger.info(f"服务已启动完成: http://{SERVER_CONFIG['host']}:{SERVER_CONFIG['port']}")

    yield

    # 关闭时清理数据库连接
    logger.info("正在关闭数据库连接...")
    await close_db()
    logger.info("数据库连接已关闭")


# 初始化FastAPI应用
app = FastAPI(
    title="智能体安全防护后端服务",
    description="基于 HardenedEngine 的安全防护检测服务（不依赖 LangChain）",
    version="2.0.0",
    lifespan=lifespan
)

# 添加CORS中间件
# 在生产环境中，应该明确指定允许的域名
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:8000",
        "http://127.0.0.1:8000",
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        # 生产环境应在此处添加实际域名
    ],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# 请求大小验证中间件
@app.middleware("http")
async def validate_request_size(request: Request, call_next):
    """
    验证请求大小的中间件
    防止过大的请求体导致服务器资源耗尽
    """
    if request.method == "POST":
        content_length = request.headers.get("content-length")
        if content_length and int(content_length) > 100 * 1024:  # 100KB限制
            return JSONResponse(
                status_code=413,
                content={"detail": "请求体过大，最大允许100KB"}
            )
    response = await call_next(request)
    return response

# API Key 鉴权中间件（健康检查豁免）
_GUARDRAIL_API_KEY = os.environ.get("GUARDRAIL_API_KEY", "")

@app.middleware("http")
async def api_key_auth(request: Request, call_next):
    """API Key 鉴权：如果配置了 GUARDRAIL_API_KEY，则验证请求头 Authorization: Bearer <key>。

    健康检查端点 (/v1/health) 豁免鉴权。
    未配置 GUARDRAIL_API_KEY 时不启用鉴权（本地开发模式）。
    """
    if _GUARDRAIL_API_KEY and request.url.path != "/health":
        auth_header = request.headers.get("Authorization", "")
        if auth_header.startswith("Bearer "):
            token = auth_header[7:]
            if token == _GUARDRAIL_API_KEY:
                return await call_next(request)
        return JSONResponse(
            status_code=401,
            content={"detail": "无效的 API Key 或缺少 Authorization 头"}
        )
    return await call_next(request)

# 挂载静态文件
app.mount("/static", StaticFiles(directory=BASE_DIR / "static"), name="static")

# HardenedEngine 实例（在 lifespan 中延迟初始化，替代 LangChainAgent）
engine: HardenedEngine | None = None
policy_loader: PolicyLoader | None = None

# 数据模型
class ChatRequest(BaseModel):
    """聊天端点的请求模型"""
    message: str
    conversation_id: str = "default"

class ChatResponse(BaseModel):
    """聊天端点的响应模型"""
    response: str
    is_blocked: bool = False
    detect_reason: str = ""
    detected_by: str | None = None
    violated_rules: List[str] = []
    response_message: str | None = None
    conversation_id: str
    timestamp: str

class SampleResponse(BaseModel):
    """测试样本端点的响应模型"""
    white_samples: List[Dict[str, str]]
    black_samples: List[Dict[str, str]]


# ============ 数据类定义 ============

@dataclass
class SecurityAnalysis:
    """安全分析结果数据类"""
    is_attack: bool
    attack_type: str | None
    security_risks: dict | None
    detected_by: str | None
    detect_reason: str | None = None


# ============ 辅助函数 ============
def load_samples() -> Dict[str, List[Dict[str, str]]]:
    """从JSON文件加载测试样本数据"""
    samples_file = BASE_DIR / "data" / "samples.json"
    try:
        with open(samples_file, 'r', encoding='utf-8') as f:
            return json.load(f)
    except FileNotFoundError:
        logger.error(f"测试样本文件未找到: {samples_file}")
        return {"white_samples": [], "black_samples": []}
    except json.JSONDecodeError as e:
        logger.error(f"测试样本文件JSON格式错误: {str(e)}")
        return {"white_samples": [], "black_samples": []}
    except Exception as e:
        logger.error(f"加载测试样本失败: {str(e)}", exc_info=True)
        return {"white_samples": [], "black_samples": []}

def log_interaction(
    conversation_id: str,
    message: str,
    response: str,
    is_blocked: bool,
    detect_reason: str = ""
) -> None:
    """记录交互详情（脱敏：不写原文到日志）"""
    import hashlib
    msg_hash = hashlib.md5(message.encode()).hexdigest()[:8] if message else "empty"
    resp_hash = hashlib.md5(response.encode()).hexdigest()[:8] if response else "empty"
    log_entry = {
        "timestamp": datetime.now().isoformat(),
        "conversation_id": conversation_id,
        "message_hash": f"len={len(message)},hash={msg_hash}",  # 不写原文
        "response_hash": f"len={len(response)},hash={resp_hash}",  # 不写原文
        "is_blocked": is_blocked,
        "detect_reason": detect_reason
    }

    if is_blocked:
        security_logger.warning(f"已拦截的交互: {log_entry}")
    else:
        logger.info(f"交互记录: {log_entry}")

def validate_message(message: str) -> None:
    """验证消息是否有效"""
    if not message or not message.strip():
        raise HTTPException(status_code=400, detail="消息不能为空")

def extract_client_info(http_request: Request) -> tuple[str | None, str | None]:
    """从HTTP请求中提取客户端信息"""
    source_ip = http_request.client.host if http_request.client else None
    user_agent = http_request.headers.get("user-agent", None)
    return source_ip, user_agent

def detect_attack_type(detect_reason: Optional[str]) -> str:
    """从检测/拦截原因中检测攻击类型"""
    if not detect_reason:
        return "unknown"

    detect_reason_lower = detect_reason.lower()

    # 处理 PII 检测：提取具体的实体类型
    if "pii detected" in detect_reason_lower:
        match = re.search(r"pii detected(?:\s+in\s+output)?:\s*([A-Z_]+(?:,\s*[A-Z_]+)*)", detect_reason, re.IGNORECASE)
        if match:
            first_type = match.group(1).split(",")[0].strip()
            return first_type
        return "pii"

    # 处理内容过滤触发
    if "内容过滤触发" in detect_reason or "content_filter" in detect_reason_lower:
        match = re.search(r"内容过滤触发:\s*([a-z_]+(?:,\s*[a-z_]+)*)", detect_reason, re.IGNORECASE)
        if match:
            first_type = match.group(1).split(",")[0].strip()
            return first_type
        return "content_filter"

    # 优先处理明确的攻击类型标识
    if "prompt_injection" in detect_reason_lower or ("提示" in detect_reason and "注入" in detect_reason):
        return "prompt_injection"
    elif "financial_fraud" in detect_reason_lower or "金融欺诈" in detect_reason:
        return "financial_fraud"
    elif "data_privacy" in detect_reason_lower or "数据隐私" in detect_reason:
        return "data_privacy"
    elif "malicious_instructions" in detect_reason_lower or "恶意指令" in detect_reason:
        return "malicious_instructions"
    elif "social_engineering" in detect_reason_lower or "社会工程" in detect_reason:
        return "social_engineering"

    # 基于关键词的推断
    if "注入" in detect_reason or "inject" in detect_reason_lower:
        return "prompt_injection"
    elif "欺诈" in detect_reason or "fraud" in detect_reason_lower:
        return "financial_fraud"
    elif "隐私" in detect_reason or "privacy" in detect_reason_lower:
        return "data_privacy"
    elif "恶意" in detect_reason or "malicious" in detect_reason_lower:
        return "malicious_instructions"
    elif "社会工程" in detect_reason or "social" in detect_reason_lower:
        return "social_engineering"

    return "unknown"

def infer_detected_by(detect_reason: Optional[str], detected_by: str = None) -> str:
    """推断拦截来源"""
    if detected_by:
        return detected_by
    
    if not detect_reason:
        return "guardrails"
    
    detect_reason_lower = detect_reason.lower()

    if any(keyword in detect_reason_lower for keyword in ["guardrails", "模式匹配", "关键字", "pattern"]):
        return "guardrails"
    elif any(keyword in detect_reason_lower for keyword in ["llm", "大模型", "模型", "安全检测", "security check"]):
        return "llm"
    elif any(keyword in detect_reason_lower for keyword in ["content_filter", "内容过滤", "有害内容"]):
        return "content_filter"

    # 默认设为guardrails（因为是第一层检测）
    return "guardrails"

def build_security_risks(
    detect_reason: str | None,
    is_attack: bool,
    detected_by: str | None,
    attack_type: str | None = None,
    extra_info: dict | None = None
) -> dict:
    """构建安全风险信息字典"""
    security_risks = {
        "detect_reason": detect_reason,
        "is_attack": is_attack,
        "detected_by": detected_by,
        "timestamp": datetime.now().isoformat(),
    }

    if attack_type and attack_type != "unknown":
        security_risks["attack_type_detailed"] = attack_type

    if extra_info:
        security_risks.update(extra_info)

    return security_risks

def analyze_security_result(
    is_blocked: bool,
    detect_reason: str | None = None,
    detected_by: str | None = None,
    violated_rules: list | None = None,
    detection_summary: dict | None = None,
) -> SecurityAnalysis:
    """
    分析安全检测结果，返回统一的安全分析信息。

    Args:
        is_blocked: 是否被拦截
        detect_reason: 检测/拦截原因
        detected_by: 检测/拦截来源（如已确定）
        violated_rules: 命中的规则列表，优先使用第一个规则名作为攻击类型
        detection_summary: 检测汇总信息（用于检测模式）

    Returns:
        SecurityAnalysis 数据类实例
    """
    # 拦截模式：使用拦截信息
    if is_blocked:
        if violated_rules and len(violated_rules) > 0:
            attack_type = violated_rules[0]
        else:
            attack_type = detect_attack_type(detect_reason)

        final_detected_by = infer_detected_by(detect_reason, detected_by)
        security_risks = build_security_risks(
            detect_reason=detect_reason,
            is_attack=True,
            detected_by=final_detected_by,
            attack_type=attack_type,
            extra_info={"action_taken": "block"},
        )
        return SecurityAnalysis(
            is_attack=True,
            attack_type=attack_type,
            security_risks=security_risks,
            detected_by=final_detected_by,
            detect_reason=detect_reason,
        )

    # 检测模式：使用 detection_summary 或 detect_reason
    if detection_summary:
        final_detect_reason = detection_summary.get("detect_reason") or detect_reason
        final_detected_by = detection_summary.get("detected_by") or detected_by
        final_violated_rules = detection_summary.get("violated_rules", [])
        if final_violated_rules and len(final_violated_rules) > 0:
            attack_type = final_violated_rules[0]
        else:
            attack_type = detect_attack_type(final_detect_reason)
        final_detected_by = infer_detected_by(final_detect_reason, final_detected_by)
        security_risks = build_security_risks(
            detect_reason=final_detect_reason,
            is_attack=True,
            detected_by=final_detected_by,
            attack_type=attack_type,
            extra_info={"action_taken": "detect", **detection_summary.get("extra", {})},
        )
        return SecurityAnalysis(
            is_attack=True,
            attack_type=attack_type,
            security_risks=security_risks,
            detected_by=final_detected_by,
            detect_reason=final_detect_reason,
        )

    # 无任何检测
    return SecurityAnalysis(
        is_attack=False,
        attack_type=None,
        security_risks=None,
        detected_by=None,
        detect_reason=None,
    )

# API端点
@app.get("/")
async def root():
    """返回服务信息"""
    return {
        "service": "智能体安全防护后端服务",
        "version": "1.0.0",
        "status": "在线",
        "endpoints": {
            "chat": "/chat (POST) - 聊天接口",
            "samples": "/samples (GET) - 获取测试用例",
            "health": "/health (GET) - 健康检查"
        }
    }

@app.get("/health")
async def health_check():
    """健康检查"""
    return {
        "status": "healthy",
        "timestamp": datetime.now().isoformat(),
        "engine_status": "initialized" if engine else "not_initialized"
    }

@app.get("/samples", response_model=SampleResponse)
async def get_samples():
    """
    获取测试样本数据

    返回白名单（正常）和黑名单（攻击）数据
    """
    try:
        samples = load_samples()
        return SampleResponse(
            white_samples=samples.get("white_samples", []),
            black_samples=samples.get("black_samples", [])
        )
    except Exception as e:
        logger.error(f"加载测试样本失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="加载测试样本失败") from e

@app.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    background_tasks: BackgroundTasks,
    http_request: Request,
    db: AsyncSession = Depends(get_db)
):
    """
    聊天端点，通过 HardenedEngine 进行安全检测

    该端点仅做安全检测，不生成 LLM 响应。
    """
    try:
        # 验证输入
        validate_message(request.message)

        # 获取客户端信息
        source_ip, user_agent = extract_client_info(http_request)

        # 通过 HardenedEngine 检测输入
        result = await engine.evaluate_input(request.message)

        is_blocked = result.decision == DECISION_BLOCK
        detect_reason = result.reason_message or ""
        detected_by = result.blocked_by
        violated_rules = result.violated_rules
        response_text = result.response_message if is_blocked else "输入安全检测通过"

        security_analysis = analyze_security_result(
            is_blocked=is_blocked,
            detect_reason=detect_reason,
            detected_by=detected_by,
            violated_rules=violated_rules,
        )

        # 保存到数据库
        try:
            # 存储脱敏后的文本（PII 已替换），不存原文
            user_input_stored = result.processed_text if result.processed_text else request.message
            await RequestLogCRUD.create_log(
                db=db,
                source_ip=source_ip,
                user_agent=user_agent,
                is_attack=security_analysis.is_attack,
                is_blocked=is_blocked,
                attack_type=security_analysis.attack_type,
                security_risks=security_analysis.security_risks,
                detect_reason=security_analysis.detect_reason or detect_reason,
                detected_by=security_analysis.detected_by,
                user_input=user_input_stored,
                response_content=response_text,
                conversation_id=request.conversation_id,
            )
        except Exception as db_error:
            logger.error(f"保存到数据库失败: {str(db_error)}", exc_info=True)

        # 在后台记录交互
        background_tasks.add_task(
            log_interaction,
            request.conversation_id,
            request.message,
            response_text,
            is_blocked,
            detect_reason
        )

        return ChatResponse(
            response=response_text,
            is_blocked=is_blocked,
            detect_reason=security_analysis.detect_reason or detect_reason,
            detected_by=security_analysis.detected_by or detected_by,
            violated_rules=violated_rules,
            response_message=result.response_message if is_blocked else None,
            conversation_id=request.conversation_id,
            timestamp=datetime.now().isoformat()
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"聊天端点错误: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"内部服务器错误") from e


# ============ Rail API 端点（供 JiuwenSwarm Rail 扩展调用）============

class RailRequest(BaseModel):
    """Rail 检测请求"""
    text: str
    request_id: Optional[str] = None
    conversation_id: Optional[str] = None
    history: Optional[List[Dict[str, Any]]] = None

class RailResponse(BaseModel):
    """Rail 检测响应"""
    decision: str
    reason_code: Optional[str] = None
    reason_message: str = ""
    violated_rules: List[str] = []
    blocked_by: str = ""
    response_message: str = ""
    processed_text: Optional[str] = None
    pii_detected: bool = False
    pii_entities: List[str] = []

@app.post("/v1/rails/input")
async def rail_input(request: RailRequest):
    """输入 Rail 检测端点（供 rail.py 调用）"""
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="text 不能为空")
    try:
        result = await engine.evaluate_input(request.text, history=request.history)
        return RailResponse(
            decision=result.decision,
            reason_code=result.reason_code,
            reason_message=result.reason_message,
            violated_rules=result.violated_rules,
            blocked_by=result.blocked_by,
            response_message=result.response_message,
            processed_text=result.processed_text,
            pii_detected=result.pii_detected,
            pii_entities=result.pii_entities,
        )
    except Exception as e:
        logger.error(f"Rail input 检测失败: {str(e)}", exc_info=True)
        return RailResponse(
            decision=DECISION_UNAVAILABLE,
            reason_code="error",
            reason_message=f"检测服务异常: {str(e)}",
        )

@app.post("/v1/rails/output")
async def rail_output(request: RailRequest):
    """输出 Rail 检测端点（供 rail.py 调用）"""
    if not request.text or not request.text.strip():
        raise HTTPException(status_code=400, detail="text 不能为空")
    try:
        result = await engine.evaluate_output(request.text)
        return RailResponse(
            decision=result.decision,
            reason_code=result.reason_code,
            reason_message=result.reason_message,
            violated_rules=result.violated_rules,
            blocked_by=result.blocked_by,
            response_message=result.response_message,
            processed_text=result.processed_text,
            pii_detected=result.pii_detected,
            pii_entities=result.pii_entities,
        )
    except Exception as e:
        logger.error(f"Rail output 检测失败: {str(e)}", exc_info=True)
        return RailResponse(
            decision=DECISION_UNAVAILABLE,
            reason_code="error",
            reason_message=f"检测服务异常: {str(e)}",
        )


@app.get("/statistics")
async def get_statistics(db: AsyncSession = Depends(get_db)):
    """
    获取安全统计数据

    返回总请求数、已拦截数、攻击数、安全率等信息
    """
    try:
        stats = await RequestLogCRUD.get_statistics(db)
        return {
            **stats.to_dict(),
            "timestamp": datetime.now().isoformat(),
        }
    except Exception as e:
        logger.error(f"获取统计数据失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取统计数据失败") from e


# ================ GuardRails规则管理API ================

# Pydantic模型定义
class GuardrailPattern(BaseModel):
    """单个正则表达式模式"""
    pattern: str

class GuardrailRule(BaseModel):
    """GuardRails规则"""
    name: str
    description: str
    patterns: List[str]
    response_message: str | None = None

class GuardrailRuleUpdate(BaseModel):
    """规则更新请求"""
    patterns: List[str]
    is_new: bool = True  # 默认为添加新规则
    description: str | None = None
    response_message: str | None = None

    @field_validator('patterns')
    def validate_patterns(cls, v):
        if not v or len(v) == 0:
            raise ValueError('正则表达式模式不能为空')
        # 过滤空字符串
        v = [p.strip() for p in v if p and p.strip()]
        if len(v) == 0:
            raise ValueError('正则表达式模式不能为空')
        return v

class EmptyRequest(BaseModel):
    """空请求体模型"""
    pass  # 完全空的模型，不要求任何字段

class GuardrailRulesResponse(BaseModel):
    """规则列表响应"""
    default_rules: List[GuardrailRule]
    custom_rules: List[GuardrailRule]
    custom_file_path: str


def load_custom_rules() -> List[Dict]:
    """加载自定义规则"""
    custom_rules_path = BASE_DIR / "data" / "guardrails_custom.json"
    if not custom_rules_path.exists():
        return []

    try:
        with open(custom_rules_path, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data.get("custom_rules", [])
    except Exception as e:
        logger.error(f"加载自定义规则失败: {e}")
        return []


def save_custom_rules(custom_rules: List[Dict]) -> bool:
    """保存自定义规则"""
    try:
        custom_rules_path = BASE_DIR / "data" / "guardrails_custom.json"
        data = {
            "version": "1.0",
            "last_updated": datetime.now().isoformat(),
            "custom_rules": custom_rules
        }

        with open(custom_rules_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

        return True
    except Exception as e:
        logger.error(f"保存自定义规则失败: {e}")
        return False


@app.get("/guardrails/rules", response_model=GuardrailRulesResponse)
async def get_guardrails_rules(db: AsyncSession = Depends(get_db)):
    """获取所有GuardRails规则（系统+自定义）"""
    try:
        # 从数据库获取所有规则
        all_rules = await GuardrailRuleCRUD.get_all_rules(db)

        # 分离系统规则和自定义规则
        system_rules = [r for r in all_rules if r.rule_type == 'system']
        custom_rules = [r for r in all_rules if r.rule_type == 'custom']

        # 转换为响应格式
        system_rule_objects = [
            GuardrailRule(
                name=rule.name,
                description=rule.description,
                patterns=rule.patterns,
                response_message=rule.response_message
            )
            for rule in system_rules
        ]

        custom_rule_objects = [
            GuardrailRule(
                name=rule.name,
                description=rule.description,
                patterns=rule.patterns,
                response_message=rule.response_message
            )
            for rule in custom_rules
        ]

        return GuardrailRulesResponse(
            default_rules=system_rule_objects,
            custom_rules=custom_rule_objects,
            custom_file_path="database"  # 使用数据库存储
        )
    except Exception as e:
        logger.error(f"获取GuardRails规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取规则失败") from e


@app.post("/guardrails/rules/reset")
async def reset_guardrails_rules(request: EmptyRequest = None, db: AsyncSession = Depends(get_db)):
    """重置所有系统默认规则为config.py中的初始默认值"""
    try:
        # 删除所有系统默认规则并重新下发
        inserted_count = await GuardrailRuleCRUD.reset_system_rules(db, GUARDRAILS_DEFAULT_RULES)

        # 重新加载策略
        reload_result = engine.reload()

        return {"status": "success", "message": f"已重置 {inserted_count} 条默认规则为初始配置", "reload": reload_result}

    except Exception as e:
        logger.error(f"重置GuardRails默认规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="重置默认规则失败") from e


@app.post("/guardrails/rules/{rule_name}")
async def add_or_update_guardrail_rule(
    rule_name: str,
    rule_update: GuardrailRuleUpdate,
    db: AsyncSession = Depends(get_db)
):
    """添加或更新GuardRails规则"""
    try:
        # 检查规则是否已存在
        existing_rule = await GuardrailRuleCRUD.get_rule_by_name(db, rule_name)

        if rule_update.is_new:
            # 添加新规则模式
            if existing_rule:
                # 规则已存在，不允许添加
                raise HTTPException(
                    status_code=400,
                    detail=f"规则 '{rule_name}' 已存在，请使用不同的名称或编辑现有规则"
                )
            else:
                # 创建新规则
                rule_type = 'custom'
        else:
            # 更新现有规则模式
            if not existing_rule:
                # 规则不存在，无法更新
                raise HTTPException(
                    status_code=404,
                    detail=f"规则 '{rule_name}' 不存在"
                )

            # 检查是否为系统规则
            if existing_rule.rule_type == 'system':
                rule_type = 'system'
            else:
                rule_type = 'custom'

        # 创建或更新规则
        rule = await GuardrailRuleCRUD.create_or_update_rule(
            db,
            name=rule_name,
            patterns=rule_update.patterns,
            description=rule_update.description,
            response_message=rule_update.response_message,
            rule_type=rule_type
        )

        await db.commit()  # 提交事务

        # 重新加载策略
        reload_result = engine.reload()
        return {"status": "success", "message": f"规则 '{rule_name}' 更新成功", "reload": reload_result}

    except HTTPException:
        # 重新抛出HTTP异常（如400错误）
        raise
    except Exception as e:
        await db.rollback()  # 回滚事务
        logger.error(f"更新GuardRails规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="更新规则失败") from e


@app.delete("/guardrails/rules/{rule_name}")
async def delete_guardrail_rule(rule_name: str, db: AsyncSession = Depends(get_db)):
    """删除自定义GuardRails规则"""
    try:
        # 删除规则
        deleted = await GuardrailRuleCRUD.delete_rule(db, rule_name)

        if not deleted:
            # 检查是否是系统规则
            rule = await GuardrailRuleCRUD.get_rule_by_name(db, rule_name)
            if rule and rule.rule_type == 'system':
                raise HTTPException(status_code=400, detail="不能删除系统规则")
            else:
                raise HTTPException(status_code=404, detail="规则不存在")

        await db.commit()  # 提交事务

        # 重新加载策略
        reload_result = engine.reload()
        return {"status": "success", "message": f"规则 '{rule_name}' 删除成功", "reload": reload_result}

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()  # 回滚事务
        logger.error(f"删除GuardRails规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="删除规则失败") from e

# ================ 白名单规则管理API ================

class WhitelistRuleCreate(BaseModel):
    """白名单规则创建/更新请求"""
    pattern: str
    description: str | None = None

    @field_validator('pattern')
    def validate_pattern(cls, v):
        if not v or not v.strip():
            raise ValueError('正则表达式模式不能为空')
        return v.strip()


class WhitelistRuleResponse(BaseModel):
    """白名单规则响应"""
    name: str
    description: str | None = None
    pattern: str
    is_active: bool
    created_at: str | None = None
    updated_at: str | None = None


@app.get("/whitelist/rules")
async def get_whitelist_rules(db: AsyncSession = Depends(get_db)):
    """获取所有白名单规则"""
    try:
        rules = await WhitelistRuleCRUD.get_all_rules(db)
        return {
            "rules": [
                {
                    "name": rule.name,
                    "description": rule.description,
                    "pattern": rule.pattern,
                    "is_active": rule.is_active,
                    "created_at": rule.created_at.isoformat() if rule.created_at else None,
                    "updated_at": rule.updated_at.isoformat() if rule.updated_at else None,
                }
                for rule in rules
            ]
        }
    except Exception as e:
        logger.error(f"获取白名单规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取白名单规则失败") from e


@app.post("/whitelist/rules/reset")
async def reset_whitelist_rules(db: AsyncSession = Depends(get_db)):
    """重置所有白名单规则（清空）"""
    try:
        deleted_count = await WhitelistRuleCRUD.delete_all_rules(db)
        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        return {
            "status": "success",
            "message": f"已清空 {deleted_count} 条白名单规则",
            "reload": reload_result,
        }
    except Exception as e:
        await db.rollback()
        logger.error(f"重置白名单规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="重置白名单规则失败") from e


@app.post("/whitelist/rules/{rule_name}")
async def add_or_update_whitelist_rule(
    rule_name: str,
    request: WhitelistRuleCreate,
    db: AsyncSession = Depends(get_db)
):
    """添加或更新白名单规则"""
    try:
        rule = await WhitelistRuleCRUD.create_or_update_rule(
            db,
            name=rule_name,
            pattern=request.pattern,
            description=request.description
        )
        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        return {
            "status": "success",
            "message": f"白名单规则 '{rule_name}' 保存成功",
            "rule": {
                "name": rule.name,
                "description": rule.description,
                "pattern": rule.pattern,
                "is_active": rule.is_active,
            },
            "reload": reload_result,
        }
    except Exception as e:
        await db.rollback()
        logger.error(f"保存白名单规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="保存白名单规则失败") from e


@app.delete("/whitelist/rules/{rule_name}")
async def delete_whitelist_rule(rule_name: str, db: AsyncSession = Depends(get_db)):
    """删除白名单规则"""
    try:
        deleted = await WhitelistRuleCRUD.delete_rule(db, rule_name)
        if not deleted:
            raise HTTPException(status_code=404, detail="规则不存在")

        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        return {
            "status": "success",
            "message": f"白名单规则 '{rule_name}' 删除成功",
            "reload": reload_result,
        }
    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"删除白名单规则失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="删除白名单规则失败") from e


# ================ PII检测API ================

class PIIDetectRequest(BaseModel):
    """PII检测请求"""
    text: str
    entity_types: Optional[List[str]] = None  # 默认为所有类型


class PIIAnonymizeRequest(BaseModel):
    """PII匿名化请求"""
    text: str
    entity_types: Optional[List[str]] = None


class PIIDetectResponse(BaseModel):
    """PII检测响应"""
    has_pii: bool
    total_count: int
    entity_counts: Dict[str, int]
    entities: List[Dict[str, Any]]
    original_text: str


class PIIAnonymizeResponse(BaseModel):
    """PII匿名化响应"""
    original_text: str
    anonymized_text: str
    has_pii: bool
    entity_counts: Dict[str, int]


@app.post("/pii/detect", response_model=PIIDetectResponse)
async def detect_pii_endpoint(request: PIIDetectRequest):
    """
    PII检测端点

    检测文本中是否包含个人身份信息（PII），
    支持中国金融场景PII和英文PII检测。
    """
    try:
        if not request.text:
            raise HTTPException(status_code=400, detail="文本不能为空")

        pii_service = get_pii_service()
        entity_types = request.entity_types or ["all"]
        results = pii_service.detect_text(request.text, entity_type=entity_types[0])

        entity_counts: Dict[str, int] = {}
        for r in results:
            entity_counts[r.entity_type] = entity_counts.get(r.entity_type, 0) + 1

        return PIIDetectResponse(
            has_pii=len(results) > 0,
            total_count=len(results),
            entity_counts=entity_counts,
            entities=[
                {
                    "type": r.entity_type,
                    "text": r.text[:30] + "..." if len(r.text) > 30 else r.text,
                    "position": (r.start, r.end),
                    "score": r.score,
                }
                for r in results
            ],
            original_text=request.text,
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"PII检测失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="PII检测失败") from e


@app.post("/pii/anonymize", response_model=PIIAnonymizeResponse)
async def anonymize_pii_endpoint(request: PIIAnonymizeRequest):
    """
    PII匿名化端点

    对文本中的个人身份信息进行匿名化/脱敏处理，
    返回脱敏后的文本。
    """
    try:
        if not request.text:
            raise HTTPException(status_code=400, detail="文本不能为空")

        pii_service = get_pii_service()
        entity_types = request.entity_types or ["all"]
        anonymized = pii_service.anonymize_text(request.text, entity_type=entity_types[0])

        # 检测脱敏后的结果
        results = pii_service.detect_text(request.text, entity_type=entity_types[0])
        entity_counts: Dict[str, int] = {}
        for r in results:
            entity_counts[r.entity_type] = entity_counts.get(r.entity_type, 0) + 1

        return PIIAnonymizeResponse(
            original_text=request.text,
            anonymized_text=anonymized,
            has_pii=len(results) > 0,
            entity_counts=entity_counts,
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"PII匿名化失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="PII匿名化失败") from e


# ================ 安全护栏配置管理API ================

class GuardrailConfigSection(BaseModel):
    """单个配置节"""
    enabled: bool
    patterns: Optional[List[str]] = None
    entities: Optional[List[str]] = None


class GuardrailConfigRequest(BaseModel):
    """安全护栏配置更新请求"""
    white_list: Optional[Dict[str, Any]] = None
    black_list: Optional[Dict[str, Any]] = None
    pii_detection: Optional[Dict[str, Any]] = None
    prompt_defense: Optional[Dict[str, Any]] = None


class GuardrailConfigResponse(BaseModel):
    """安全护栏配置响应"""
    config: Dict[str, Any]
    pii_entity_options: List[Dict[str, str]]
    security_check_prompt: str


@app.get("/config/guardrail", response_model=GuardrailConfigResponse)
async def get_guardrail_config_endpoint():
    """
    获取当前安全护栏配置

    返回运行时配置、PII实体选项列表、提示词防御内容。
    """
    try:
        config = get_guardrail_config()
        return GuardrailConfigResponse(
            config=config,
            pii_entity_options=PII_ENTITY_OPTIONS,
            security_check_prompt=SECURITY_CHECK_PROMPT.strip(),
        )
    except Exception as e:
        logger.error(f"获取安全护栏配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取配置失败") from e


@app.post("/config/guardrail")
async def update_guardrail_config_endpoint(request: GuardrailConfigRequest):
    """
    更新安全护栏配置并动态生效

    更新后自动通知Agent重新加载配置，无需重启服务。
    """
    try:
        updates = {}
        if request.white_list is not None:
            updates["white_list"] = request.white_list
        if request.black_list is not None:
            updates["black_list"] = request.black_list
        if request.pii_detection is not None:
            updates["pii_detection"] = request.pii_detection
        if request.prompt_defense is not None:
            updates["prompt_defense"] = request.prompt_defense

        updated_config = update_guardrail_config(updates)

        # 重新加载策略
        reload_result = engine.reload()

        logger.info(f"安全护栏配置已更新: {list(updates.keys())}")

        return {
            "status": "success",
            "message": "配置已更新并动态生效",
            "config": updated_config,
            "agent_reload": reload_result,
        }

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"更新安全护栏配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="更新配置失败") from e


# ================ PII配置管理API ================

class PIIConfigResponse(BaseModel):
    """PII配置响应"""
    id: int
    input_enabled: bool
    output_enabled: bool
    input_entities: List[str]
    output_entities: List[str]
    use_presidio: bool
    threshold: float
    anonymize_input: bool
    anonymize_output: bool
    input_action_mode: str
    output_action_mode: str
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


class PIIConfigUpdateRequest(BaseModel):
    """PII配置更新请求"""
    input_enabled: Optional[bool] = None
    output_enabled: Optional[bool] = None
    input_entities: Optional[List[str]] = None
    output_entities: Optional[List[str]] = None
    use_presidio: Optional[bool] = None
    threshold: Optional[float] = None
    anonymize_input: Optional[bool] = None
    anonymize_output: Optional[bool] = None
    input_action_mode: Optional[str] = None
    output_action_mode: Optional[str] = None


@app.get("/config/pii", response_model=PIIConfigResponse)
async def get_pii_config_endpoint(db: AsyncSession = Depends(get_db)):
    """
    获取PII检测配置

    从数据库返回当前PII检测配置。
    """
    try:
        config = await PIIConfigCRUD.get_or_create_config(db)
        return PIIConfigResponse(**config.to_dict())
    except Exception as e:
        logger.error(f"获取PII配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取PII配置失败") from e


@app.post("/config/pii")
async def update_pii_config_endpoint(
    request: PIIConfigUpdateRequest,
    db: AsyncSession = Depends(get_db)
):
    """
    更新PII检测配置并动态生效

    更新数据库中的PII配置，并立即重新加载Agent的PII检测服务。
    """
    try:
        # 更新数据库配置
        config = await PIIConfigCRUD.update_config(
            db,
            input_enabled=request.input_enabled,
            output_enabled=request.output_enabled,
            input_entities=request.input_entities,
            output_entities=request.output_entities,
            use_presidio=request.use_presidio,
            threshold=request.threshold,
            anonymize_input=request.anonymize_input,
            anonymize_output=request.anonymize_output,
            input_action_mode=request.input_action_mode,
            output_action_mode=request.output_action_mode,
        )
        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        logger.info(f"PII配置已更新并重新加载: input_enabled={config.input_enabled}, output_enabled={config.output_enabled}")

        return {
            "status": "success",
            "message": "PII配置已更新并动态生效",
            "config": config.to_dict(),
            "agent_reload": reload_result,
        }

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"更新PII配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="更新PII配置失败") from e


# ================ 提示词防御配置管理API ================

class PromptDefenseConfigResponse(BaseModel):
    """提示词防御配置响应"""
    id: int
    enabled: bool
    prompt_content: str
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


class PromptDefenseConfigUpdateRequest(BaseModel):
    """提示词防御配置更新请求"""
    enabled: Optional[bool] = None
    prompt_content: Optional[str] = None


@app.get("/config/prompt_defense", response_model=PromptDefenseConfigResponse)
async def get_prompt_defense_config_endpoint(db: AsyncSession = Depends(get_db)):
    """
    获取提示词防御配置

    从数据库返回当前提示词防御配置。
    """
    try:
        config = await PromptDefenseConfigCRUD.get_or_create_config(db)
        return PromptDefenseConfigResponse(**config.to_dict())
    except Exception as e:
        logger.error(f"获取提示词防御配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取提示词防御配置失败") from e


@app.post("/config/prompt_defense")
async def update_prompt_defense_config_endpoint(
    request: PromptDefenseConfigUpdateRequest,
    db: AsyncSession = Depends(get_db)
):
    """
    更新提示词防御配置并动态生效

    更新数据库中的提示词防御配置，并立即重新加载Agent的配置。
    """
    try:
        # 更新数据库配置
        config = await PromptDefenseConfigCRUD.update_config(
            db,
            enabled=request.enabled,
            prompt_content=request.prompt_content,
        )
        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        logger.info(f"提示词防御配置已更新并重新加载: enabled={config.enabled}")

        return {
            "status": "success",
            "message": "提示词防御配置已更新并动态生效",
            "config": config.to_dict(),
            "agent_reload": reload_result,
        }

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"更新提示词防御配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="更新提示词防御配置失败") from e


# ================ 提示词防御重置API ================

@app.post("/config/prompt_defense/reset")
async def reset_prompt_defense_config_endpoint(db: AsyncSession = Depends(get_db)):
    """
    重置提示词防御配置为系统默认值

    将 SECURITY_CHECK_PROMPT 从 config.py 重新覆盖到数据库。
    """
    try:
        # 更新数据库配置为默认值
        config = await PromptDefenseConfigCRUD.update_config(
            db,
            prompt_content=SECURITY_CHECK_PROMPT,
        )
        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        logger.info("提示词防御配置已重置为系统默认值")

        return {
            "status": "success",
            "message": "提示词防御配置已重置为系统默认值",
            "config": config.to_dict(),
            "agent_reload": reload_result,
        }

    except Exception as e:
        await db.rollback()
        logger.error(f"重置提示词防御配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="重置提示词防御配置失败") from e


# ================ 监控面板 Dashboard API ================

def get_period_start_time(period: str) -> datetime | None:
    """Convert period string to start datetime."""
    now = datetime.now()
    period_map = {
        "24h": now - timedelta(hours=24),
        "7d": now - timedelta(days=7),
        "30d": now - timedelta(days=30),
    }
    return period_map.get(period)


@app.get("/dashboard/statistics")
async def get_dashboard_statistics(
    period: str = "24h",
    db: AsyncSession = Depends(get_db)
):
    """
    Get security statistics for dashboard with time period filtering.

    Query params:
        period: "24h" | "7d" | "30d" | "all" (default: "24h")
    """
    try:
        if period not in ("24h", "7d", "30d", "all"):
            raise HTTPException(status_code=400, detail="period must be 24h, 7d, 30d, or all")

        stats = await RequestLogCRUD.get_statistics_for_period(db, period=period)
        return {
            "period": period,
            **stats.to_dict(),
            "timestamp": datetime.now().isoformat(),
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取Dashboard统计数据失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取统计数据失败") from e


@app.get("/dashboard/attack-types")
async def get_dashboard_attack_types(
    period: str = "24h",
    db: AsyncSession = Depends(get_db)
):
    """
    Get attack type distribution for dashboard.

    Query params:
        period: "24h" | "7d" | "30d" | "all" (default: "24h")
    """
    try:
        if period not in ("24h", "7d", "30d", "all"):
            raise HTTPException(status_code=400, detail="period must be 24h, 7d, 30d, or all")

        now = datetime.now()
        start_time = get_period_start_time(period)
        distribution = await RequestLogCRUD.get_attack_type_distribution(
            db,
            start_time=start_time,
            end_time=now,
        )
        return {
            "period": period,
            "distribution": distribution,
            "timestamp": datetime.now().isoformat(),
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取攻击类型分布失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取攻击类型分布失败") from e


@app.get("/dashboard/blocked-by")
async def get_dashboard_detected_by(
    period: str = "24h",
    db: AsyncSession = Depends(get_db)
):
    """
    Get detected_by source distribution for dashboard.

    Query params:
        period: "24h" | "7d" | "30d" | "all" (default: "24h")
    """
    try:
        if period not in ("24h", "7d", "30d", "all"):
            raise HTTPException(status_code=400, detail="period must be 24h, 7d, 30d, or all")

        now = datetime.now()
        start_time = get_period_start_time(period)
        distribution = await RequestLogCRUD.get_detected_by_distribution(
            db,
            start_time=start_time,
            end_time=now,
        )
        return {
            "period": period,
            "distribution": distribution,
            "timestamp": datetime.now().isoformat(),
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取拦截来源分布失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取拦截来源分布失败") from e


@app.get("/dashboard/trends")
async def get_dashboard_trends(
    period: str = "24h",
    db: AsyncSession = Depends(get_db)
):
    """
    Get time series trends for dashboard.

    Query params:
        period: "24h" | "7d" | "30d" | "all" (default: "24h")
    """
    try:
        if period not in ("24h", "7d", "30d", "all"):
            raise HTTPException(status_code=400, detail="period must be 24h, 7d, 30d, or all")

        now = datetime.now()
        if period == "all":
            start_time = await RequestLogCRUD.get_earliest_log_time(db)
            if start_time is None:
                return {
                    "period": period,
                    "interval_hours": 1,
                    "data": [],
                    "timestamp": now.isoformat(),
                }
            # Calculate interval to keep data points under 30
            total_hours = max(1, int((now - start_time).total_seconds() / 3600))
            if total_hours <= 24:
                interval_hours = 1
            elif total_hours <= 168:
                interval_hours = 6
            elif total_hours <= 720:
                interval_hours = 24
            else:
                interval_hours = max(1, int(total_hours / 30))
        else:
            interval_map = {
                "24h": (now - timedelta(hours=24), 1),
                "7d": (now - timedelta(days=7), 6),
                "30d": (now - timedelta(days=30), 24),
            }
            start_time, interval_hours = interval_map[period]

        data = await RequestLogCRUD.get_time_series_statistics(
            db,
            start_time=start_time,
            end_time=now,
            interval_hours=interval_hours,
        )

        # Simplify response: only keep essential fields
        simplified = []
        for item in data:
            simplified.append({
                "time": item["time"],
                "total": item["total_requests"],
                "blocked": item["blocked_requests"],
                "attacks": item["attack_requests"],
                "safe": item["safe_requests"],
            })

        return {
            "period": period,
            "interval_hours": interval_hours,
            "data": simplified,
            "timestamp": now.isoformat(),
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取趋势数据失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取趋势数据失败") from e


@app.get("/dashboard/logs")
async def get_dashboard_logs(
    limit: int = 20,
    offset: int = 0,
    is_attack: bool | None = None,
    is_blocked: bool | None = None,
    attack_type: str | None = None,
    period: str = "24h",
    db: AsyncSession = Depends(get_db)
):
    """
    Get paginated and filtered logs for dashboard.

    Query params:
        limit: max records per page (default: 20, max: 100)
        offset: skip offset (default: 0)
        is_attack: filter by attack status (optional)
        is_blocked: filter by blocked status (optional)
        attack_type: filter by attack type (optional)
        period: time period filter (24h, 7d, 30d, all)
    """
    try:
        if period not in ("24h", "7d", "30d", "all"):
            raise HTTPException(status_code=400, detail="period must be 24h, 7d, 30d, or all")

        limit = min(max(limit, 1), 100)
        offset = max(offset, 0)
        now = datetime.now()
        start_time = get_period_start_time(period)

        logs, total = await RequestLogCRUD.get_filtered_logs(
            db,
            limit=limit,
            offset=offset,
            is_attack=is_attack,
            is_blocked=is_blocked,
            attack_type=attack_type,
            start_time=start_time,
            end_time=now,
        )

        return {
            "logs": [log.to_dict() for log in logs],
            "total": total,
            "limit": limit,
            "offset": offset,
            "has_more": offset + limit < total,
            "period": period,
            "timestamp": datetime.now().isoformat(),
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"获取日志列表失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取日志列表失败") from e


# ================ 内容过滤 API ================

class ContentFilterConfigResponse(BaseModel):
    """内容过滤配置响应"""
    id: int
    input_enabled: bool
    output_enabled: bool
    action_mode: str
    created_at: Optional[str] = None
    updated_at: Optional[str] = None


class ContentFilterConfigUpdateRequest(BaseModel):
    """内容过滤配置更新请求"""
    input_enabled: Optional[bool] = None
    output_enabled: Optional[bool] = None
    action_mode: Optional[str] = None


class ContentFilterClassifyRequest(BaseModel):
    """内容过滤分类请求"""
    text: str


class ContentFilterClassifyResponse(BaseModel):
    """内容过滤分类响应"""
    category_scores: Dict[str, float]
    triggered_categories: List[str]
    max_score: float
    max_category: Optional[str]
    is_blocked: bool
    processing_time_ms: float


@app.get("/config/content_filter", response_model=ContentFilterConfigResponse)
async def get_content_filter_config_endpoint(db: AsyncSession = Depends(get_db)):
    """
    获取内容过滤配置

    从数据库返回当前内容过滤配置。
    """
    try:
        config = await ContentFilterConfigCRUD.get_or_create_config(db)
        return ContentFilterConfigResponse(**config.to_dict())
    except Exception as e:
        logger.error(f"获取内容过滤配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取内容过滤配置失败") from e


@app.post("/config/content_filter")
async def update_content_filter_config_endpoint(
    request: ContentFilterConfigUpdateRequest,
    db: AsyncSession = Depends(get_db)
):
    """
    更新内容过滤配置并动态生效

    更新数据库中的内容过滤配置，并立即重新加载Agent的内容过滤服务。
    """
    try:
        # 验证 action_mode
        if request.action_mode is not None and request.action_mode not in ("block", "detect"):
            raise HTTPException(status_code=400, detail="action_mode 必须是 'block' 或 'detect'")

        # 更新数据库配置
        config = await ContentFilterConfigCRUD.update_config(
            db,
            input_enabled=request.input_enabled,
            output_enabled=request.output_enabled,
            action_mode=request.action_mode,
        )
        await db.commit()

        # 重新加载策略
        reload_result = engine.reload()

        logger.info(
            f"内容过滤配置已更新并重新加载: input_enabled={config.input_enabled}, "
            f"output_enabled={config.output_enabled}, action_mode={config.action_mode}"
        )

        return {
            "status": "success",
            "message": "内容过滤配置已更新并动态生效",
            "config": config.to_dict(),
            "agent_reload": reload_result,
        }

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"更新内容过滤配置失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="更新内容过滤配置失败") from e


@app.post("/content_filter/classify", response_model=ContentFilterClassifyResponse)
async def classify_content_endpoint(request: ContentFilterClassifyRequest):
    """
    内容过滤分类检测端点

    对输入文本进行内容分类检测，返回各类别分数和是否触发拦截。
    此端点仅用于测试/调试，不修改数据库。
    """
    try:
        if not request.text:
            raise HTTPException(status_code=400, detail="文本不能为空")

        cf_service = get_classifier_service()
        result = await cf_service.classify(request.text)

        return ContentFilterClassifyResponse(
            category_scores=result.category_scores,
            triggered_categories=result.triggered_categories,
            max_score=result.max_score,
            max_category=result.max_category,
            is_blocked=result.is_blocked,
            processing_time_ms=result.processing_time_ms,
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"内容过滤分类失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="内容过滤分类失败") from e


@app.get("/content_filter/categories")
async def get_content_filter_categories(db: AsyncSession = Depends(get_db)):
    """
    获取内容过滤类别列表（从数据库读取）

    返回所有类别、锚点文本和阈值，包含向量化状态。
    """
    try:
        categories = await ContentFilterCategoryCRUD.get_all_categories(db)
        result = []
        for category in categories:
            anchors = await ContentFilterAnchorCRUD.get_anchors_by_category(db, category.id)
            result.append({
                "id": category.id,
                "name": category.name,
                "description": category.description,
                "category_type": category.category_type,
                "threshold": float(category.threshold),
                "is_active": category.is_active,
                "anchors": [
                    {
                        "id": a.id,
                        "text": a.text,
                        "has_embedding": a.embedding is not None,
                        "is_active": a.is_active,
                    }
                    for a in anchors
                ],
            })

        return {"categories": result}

    except Exception as e:
        logger.error(f"获取内容过滤类别失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail="获取内容过滤类别失败") from e


class CreateCategoryRequest(BaseModel):
    """创建自定义类别请求"""
    name: str
    threshold: float = 0.75
    description: str = ""
    anchors: List[str]


class UpdateCategoryRequest(BaseModel):
    """更新类别请求"""
    threshold: Optional[float] = None
    description: Optional[str] = None
    is_active: Optional[bool] = None
    anchors: Optional[List[str]] = None


class AddAnchorRequest(BaseModel):
    """添加锚点请求"""
    text: str


@app.post("/content_filter/categories")
async def create_category_endpoint(
    request: CreateCategoryRequest,
    db: AsyncSession = Depends(get_db)
):
    """创建自定义内容过滤类别"""
    try:
        # 检查名称是否已存在
        existing = await ContentFilterCategoryCRUD.get_category_by_name(db, request.name)
        if existing:
            raise HTTPException(status_code=400, detail=f"类别 '{request.name}' 已存在")

        # 创建类别
        category = await ContentFilterCategoryCRUD.create_category(
            db,
            name=request.name,
            threshold=request.threshold,
            description=request.description,
            category_type='custom',
            is_active=True,
        )

        # 创建锚点（无 embedding，后续自动向量化）
        for text in request.anchors:
            await ContentFilterAnchorCRUD.create_anchor(
                db, category_id=category.id, text=text, embedding=None, is_active=True
            )

        await db.commit()

        # 向量化新锚点
        cf_service = get_classifier_service()
        vectorized = await cf_service.vectorize_missing_anchors(db)
        await db.commit()

        # 重新加载到内存
        await cf_service.load_from_db(db)

        logger.info(f"创建自定义类别: {request.name}, 锚点数: {len(request.anchors)}, 向量化: {vectorized}")
        return {"status": "success", "message": f"类别 '{request.name}' 创建成功", "vectorized": vectorized}

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"创建类别失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"创建类别失败: {str(e)}") from e


@app.put("/content_filter/categories/{name}")
async def update_category_endpoint(
    name: str,
    request: UpdateCategoryRequest,
    db: AsyncSession = Depends(get_db)
):
    """修改内容过滤类别（支持修改锚点）"""
    try:
        category = await ContentFilterCategoryCRUD.get_category_by_name(db, name)
        if not category:
            raise HTTPException(status_code=404, detail=f"类别 '{name}' 不存在")

        # 更新类别属性
        await ContentFilterCategoryCRUD.update_category(
            db,
            name=name,
            threshold=request.threshold,
            description=request.description,
            is_active=request.is_active,
        )

        # 如果提供了新的锚点列表，智能判断是否有变化
        anchors_changed = False
        if request.anchors is not None:
            # 获取现有锚点文本集合
            existing_anchors = await ContentFilterAnchorCRUD.get_anchors_by_category(db, category.id)
            existing_texts = set(a.text.strip() for a in existing_anchors)
            new_texts = set(text.strip() for text in request.anchors if text.strip())

            # 只有当锚点文本有变化时才删除重建
            if existing_texts != new_texts:
                # 删除旧锚点
                await ContentFilterAnchorCRUD.delete_anchors_by_category(db, category.id)
                # 创建新锚点
                for text in request.anchors:
                    if text.strip():
                        await ContentFilterAnchorCRUD.create_anchor(
                            db, category_id=category.id, text=text.strip(), embedding=None, is_active=True
                        )
                anchors_changed = True

        await db.commit()

        # 只有当锚点有变化时才重新向量化
        if anchors_changed:
            cf_service = get_classifier_service()
            vectorized = await cf_service.vectorize_missing_anchors(db)
            await db.commit()
        else:
            vectorized = 0

        # 重新加载到内存
        cf_service = get_classifier_service()
        await cf_service.load_from_db(db)

        if anchors_changed:
            logger.info(f"更新类别: {name}, 锚点已变化, 向量化: {vectorized}")
        else:
            logger.info(f"更新类别: {name}, 锚点无变化, 跳过向量化")
        return {"status": "success", "message": f"类别 '{name}' 更新成功", "vectorized": vectorized, "anchors_changed": anchors_changed}

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"更新类别失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"更新类别失败: {str(e)}") from e


@app.delete("/content_filter/categories/{name}")
async def delete_category_endpoint(name: str, db: AsyncSession = Depends(get_db)):
    """删除自定义内容过滤类别（系统类别不可删除）"""
    try:
        category = await ContentFilterCategoryCRUD.get_category_by_name(db, name)
        if not category:
            raise HTTPException(status_code=404, detail=f"类别 '{name}' 不存在")
        if category.category_type == 'system':
            raise HTTPException(status_code=403, detail="系统默认类别不可删除")

        deleted = await ContentFilterCategoryCRUD.delete_category(db, name)
        if not deleted:
            raise HTTPException(status_code=500, detail=f"删除类别 '{name}' 失败")

        await db.commit()

        # 重新加载到内存
        cf_service = get_classifier_service()
        await cf_service.load_from_db(db)

        logger.info(f"删除自定义类别: {name}")
        return {"status": "success", "message": f"类别 '{name}' 已删除"}

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"删除类别失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"删除类别失败: {str(e)}") from e


@app.post("/content_filter/categories/{name}/anchors")
async def add_anchor_endpoint(
    name: str,
    request: AddAnchorRequest,
    db: AsyncSession = Depends(get_db)
):
    """为类别添加锚点"""
    try:
        category = await ContentFilterCategoryCRUD.get_category_by_name(db, name)
        if not category:
            raise HTTPException(status_code=404, detail=f"类别 '{name}' 不存在")

        anchor = await ContentFilterAnchorCRUD.create_anchor(
            db, category_id=category.id, text=request.text, embedding=None, is_active=True
        )
        await db.commit()

        # 向量化新锚点
        cf_service = get_classifier_service()
        vectorized = await cf_service.vectorize_missing_anchors(db)
        await db.commit()

        # 重新加载到内存
        await cf_service.load_from_db(db)

        logger.info(f"为类别 '{name}' 添加锚点 (len={len(request.text)})")
        return {"status": "success", "message": "锚点添加成功", "anchor_id": anchor.id, "vectorized": vectorized}

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"添加锚点失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"添加锚点失败: {str(e)}") from e


@app.delete("/content_filter/categories/{name}/anchors/{anchor_id}")
async def delete_anchor_endpoint(
    name: str,
    anchor_id: int,
    db: AsyncSession = Depends(get_db)
):
    """删除锚点"""
    try:
        # 验证类别存在
        category = await ContentFilterCategoryCRUD.get_category_by_name(db, name)
        if not category:
            raise HTTPException(status_code=404, detail=f"类别 '{name}' 不存在")

        from sqlalchemy import select as sa_select
        from database.models import ContentFilterAnchor as CFAnchorModel
        result = await db.execute(
            sa_select(CFAnchorModel).where(CFAnchorModel.id == anchor_id, CFAnchorModel.category_id == category.id)
        )
        anchor = result.scalar_one_or_none()
        if not anchor:
            raise HTTPException(status_code=404, detail="锚点不存在")

        await db.delete(anchor)
        await db.flush()
        await db.commit()

        # 重新加载到内存
        cf_service = get_classifier_service()
        await cf_service.load_from_db(db)

        logger.info(f"删除锚点: {anchor_id} from 类别 '{name}'")
        return {"status": "success", "message": "锚点已删除"}

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"删除锚点失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"删除锚点失败: {str(e)}") from e


@app.post("/content_filter/categories/reset")
async def reset_categories_endpoint(db: AsyncSession = Depends(get_db)):
    """重置默认类别为 categories.py 的原始配置（自定义类别保留）"""
    try:
        from src.classifier.categories import DEFAULT_THRESHOLDS, CATEGORY_ANCHORS

        default_categories = {
            name: {"threshold": DEFAULT_THRESHOLDS.get(name, 0.75), "description": f"系统默认类别: {name}"}
            for name in DEFAULT_THRESHOLDS.keys()
        }

        # 重置系统类别
        reset_count = await ContentFilterCategoryCRUD.reset_system_categories(db, default_categories)
        await db.flush()

        # 重新初始化系统锚点
        anchor_count = await ContentFilterAnchorCRUD.initialize_system_anchors(db, CATEGORY_ANCHORS)
        await db.flush()

        # 向量化新锚点
        cf_service = get_classifier_service()
        vectorized = await cf_service.vectorize_missing_anchors(db)
        await db.commit()

        # 重新加载到内存
        await cf_service.load_from_db(db)

        logger.info(f"重置默认类别完成: 类别 {reset_count}, 锚点 {anchor_count}, 向量化 {vectorized}")
        return {
            "status": "success",
            "message": "默认类别已重置为原始配置",
            "categories_reset": reset_count,
            "anchors_created": anchor_count,
            "vectorized": vectorized,
        }

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"重置类别失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"重置类别失败: {str(e)}") from e


@app.post("/content_filter/revectorize")
async def revectorize_endpoint(db: AsyncSession = Depends(get_db)):
    """重新向量化所有锚点（重置 embedding 后重新计算）"""
    try:
        # 重置所有锚点的 embedding 为 NULL
        reset_count = await ContentFilterAnchorCRUD.reset_all_embeddings(db)
        await db.flush()
        logger.info(f"已重置 {reset_count} 个锚点的 embedding")

        # 向量化缺失的锚点
        cf_service = get_classifier_service()
        vectorized = await cf_service.vectorize_missing_anchors(db)
        await db.commit()

        # 重新加载到内存
        loaded = await cf_service.load_from_db(db)

        logger.info(f"重新向量化完成: 重置 {reset_count} 个, 向量化 {vectorized} 个")
        return {
            "status": "success",
            "message": f"重新向量化完成",
            "reset_count": reset_count,
            "vectorized": vectorized,
            "loaded": loaded,
        }

    except HTTPException:
        raise
    except Exception as e:
        await db.rollback()
        logger.error(f"重新向量化失败: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"重新向量化失败: {str(e)}") from e


# 错误处理器
@app.exception_handler(HTTPException)
async def http_exception_handler(request, exc):
    """处理HTTP异常并记录日志"""
    logger.warning(f"HTTP异常: {exc.status_code} - {exc.detail}")
    return JSONResponse(
        status_code=exc.status_code,
        content={"detail": exc.detail}
    )

@app.exception_handler(Exception)
async def general_exception_handler(request, exc):
    """处理一般异常并记录日志"""
    # 忽略Windows网络断开错误
    if isinstance(exc, OSError) and exc.winerror == 64:
        # WinError 64: 指定的网络名不再可用 - 客户端断开连接
        return JSONResponse(
            status_code=499,  # Client Closed Request
            content={"detail": "客户端已断开连接"}
        )
    
    logger.error(f"未处理的异常: {str(exc)}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": "内部服务器错误"}
    )

if __name__ == "__main__":
    import uvicorn
    import logging
    import sys
    
    # 自定义日志过滤器，抑制Windows网络断开错误
    class NetworkErrorFilter(logging.Filter):
        def filter(self, record):
            # 只过滤掉Windows网络断开相关的特定错误日志
            msg = record.getMessage()
            
            # 过滤WinError 64相关错误
            if 'WinError 64' in msg:
                return False
            
            # 过滤socket accept失败
            if 'Accept failed on a socket' in msg:
                return False
            
            # 过滤Task exception was never retrieved（通常是网络断开导致）
            if 'Task exception was never retrieved' in msg:
                # 检查是否是OSError相关的异常
                if hasattr(record, 'exc_info') and record.exc_info:
                    exc_type = record.exc_info[0]
                    if exc_type and exc_type.__name__ == 'OSError':
                        return False
            
            return True
    
    # 应用过滤器到asyncio和uvicorn的日志器
    for logger_name in ['asyncio', 'uvicorn.error', 'uvicorn.access']:
        log = logging.getLogger(logger_name)
        log.addFilter(NetworkErrorFilter())
    
    # 设置asyncio的日志级别为WARNING，减少噪音
    logging.getLogger('asyncio').setLevel(logging.WARNING)
    
    logger.info(f"启动服务器 {SERVER_CONFIG['host']}:{SERVER_CONFIG['port']}")
    uvicorn.run(
        "server:app",
        host=SERVER_CONFIG["host"],
        port=SERVER_CONFIG["port"],
        reload=SERVER_CONFIG["reload"],
        log_level="warning"  # 降低uvicorn日志级别
    )