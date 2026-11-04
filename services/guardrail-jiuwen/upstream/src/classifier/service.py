"""
内容过滤分类服务。

基于硅基流动 Embedding API 的零样本分类器，
通过计算输入文本与各类别锚点的余弦相似度进行内容过滤。

锚点和向量化结果现在持久化到数据库，首次启动时自动加载。
"""
import logging
import re
import time
from dataclasses import dataclass
from typing import Dict, List, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from src.classifier import fetch_embedding, fetch_embeddings, cosine_similarity

logger = logging.getLogger("agent_service")
security_logger = logging.getLogger("security")


@dataclass
class ClassificationResult:
    """分类结果数据类。"""

    category_scores: Dict[str, float]
    triggered_categories: List[str]
    max_score: float
    max_category: Optional[str]
    is_blocked: bool
    processing_time_ms: float


class ContentFilterService:
    """
    内容过滤服务。

    单例模式管理。锚点和阈值从数据库加载，
    向量化结果持久化到数据库，运行时使用内存缓存。
    """

    def __init__(
        self,
        input_enabled: bool = True,
        output_enabled: bool = True,
        action_mode: str = "block",
    ):
        self.input_enabled = input_enabled
        self.output_enabled = output_enabled
        self.action_mode = action_mode

        # 运行时数据（从数据库加载后填充）
        self._thresholds: Dict[str, float] = {}
        self._enabled_categories: List[str] = []
        self._anchor_embeddings: Dict[str, List[List[float]]] = {}
        self._anchors_loaded = False

    # ------------------------------------------------------------------ #
    # 配置管理
    # ------------------------------------------------------------------ #

    def update_config(
        self,
        input_enabled: Optional[bool] = None,
        output_enabled: Optional[bool] = None,
        action_mode: Optional[str] = None,
    ) -> None:
        """热更新开关和模式配置。"""
        if input_enabled is not None:
            self.input_enabled = input_enabled
        if output_enabled is not None:
            self.output_enabled = output_enabled
        if action_mode is not None:
            self.action_mode = action_mode

        logger.info(
            f"ContentFilterService 配置更新: input_enabled={self.input_enabled}, "
            f"output_enabled={self.output_enabled}, action_mode={self.action_mode}"
        )

    # ------------------------------------------------------------------ #
    # 从数据库加载
    # ------------------------------------------------------------------ #

    async def load_from_db(self, db: AsyncSession) -> bool:
        """从数据库加载所有活跃类别的锚点和阈值到内存。"""
        try:
            from database.crud import ContentFilterCategoryCRUD, ContentFilterAnchorCRUD

            categories = await ContentFilterCategoryCRUD.get_all_categories(db, active_only=True)
            if not categories:
                logger.warning("数据库中无活跃的内容过滤类别")
                self._anchors_loaded = False
                return False

            self._enabled_categories = [c.name for c in categories]
            self._thresholds = {c.name: float(c.threshold) for c in categories}
            self._anchor_embeddings = {}

            total_anchors = 0
            for category in categories:
                anchors = await ContentFilterAnchorCRUD.get_anchors_by_category(
                    db, category.id, active_only=True
                )
                embeddings = []
                for anchor in anchors:
                    if anchor.embedding:
                        embeddings.append(anchor.embedding)
                        total_anchors += 1
                self._anchor_embeddings[category.name] = embeddings

            self._anchors_loaded = True
            logger.info(
                f"从数据库加载完成: {len(self._enabled_categories)} 个类别, "
                f"{total_anchors} 个有效锚点"
            )
            return True

        except Exception as e:
            logger.error(f"从数据库加载内容过滤数据失败: {e}", exc_info=True)
            return False

    def invalidate_cache(self) -> None:
        """使内存缓存失效，下次 classify 前会重新加载。"""
        self._anchors_loaded = False
        self._anchor_embeddings = {}
        self._thresholds = {}
        self._enabled_categories = []
        logger.info("内容过滤内存缓存已失效")

    # ------------------------------------------------------------------ #
    # 向量化缺失的锚点
    # ------------------------------------------------------------------ #

    async def vectorize_missing_anchors(self, db: AsyncSession, batch_size: int = 32) -> int:
        """向量化数据库中缺少 embedding 的锚点，并更新数据库。

        Args:
            db: 数据库会话
            batch_size: 每批向量化数量（SiliconFlow API 最大支持 32）

        Returns:
            成功向量化的锚点数量
        """
        try:
            from database.crud import ContentFilterAnchorCRUD

            anchors = await ContentFilterAnchorCRUD.get_anchors_without_embedding(db)
            if not anchors:
                logger.info("所有锚点已完成向量化")
                return 0

            logger.info(f"需要向量化的锚点数量: {len(anchors)}")

            total_updated = 0
            for i in range(0, len(anchors), batch_size):
                batch = anchors[i : i + batch_size]
                texts = [a.text for a in batch]
                embeddings = await fetch_embeddings(texts)

                if not embeddings or len(embeddings) != len(batch):
                    logger.error(
                        f"向量化 API 返回数量不匹配: 请求 {len(batch)}, "
                        f"返回 {len(embeddings) if embeddings else 0}"
                    )
                    continue

                for anchor, embedding in zip(batch, embeddings):
                    await ContentFilterAnchorCRUD.update_anchor_embedding(db, anchor.id, embedding)
                    total_updated += 1

                logger.info(f"锚点向量化进度: {total_updated}/{len(anchors)}")

            logger.info(f"锚点向量化完成: {total_updated}/{len(anchors)}")
            return total_updated

        except Exception as e:
            logger.error(f"锚点向量化失败: {e}", exc_info=True)
            return 0

    # ------------------------------------------------------------------ #
    # 核心分类逻辑
    # ------------------------------------------------------------------ #

    async def classify(self, text: str, is_output: bool = False) -> ClassificationResult:
        """
        对输入文本进行分类检测。

        Args:
            text: 待检测文本
            is_output: 是否为输出阶段检测

        Returns:
            ClassificationResult 包含各类别分数、触发类别、是否拦截等
        """
        start_time = time.perf_counter()

        # 快速路径：服务禁用
        if is_output and not self.output_enabled:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            return ClassificationResult(
                category_scores={},
                triggered_categories=[],
                max_score=0.0,
                max_category=None,
                is_blocked=False,
                processing_time_ms=round(elapsed_ms, 2),
            )
        if not is_output and not self.input_enabled:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            return ClassificationResult(
                category_scores={},
                triggered_categories=[],
                max_score=0.0,
                max_category=None,
                is_blocked=False,
                processing_time_ms=round(elapsed_ms, 2),
            )

        # 检查是否已加载
        if not self._anchors_loaded:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            logger.warning("锚点数据未从数据库加载，跳过内容过滤")
            return ClassificationResult(
                category_scores={},
                triggered_categories=[],
                max_score=0.0,
                max_category=None,
                is_blocked=False,
                processing_time_ms=round(elapsed_ms, 2),
            )

        # 获取输入文本的 embedding
        input_embedding = await fetch_embedding(text)
        if input_embedding is None:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            return ClassificationResult(
                category_scores={},
                triggered_categories=[],
                max_score=0.0,
                max_category=None,
                is_blocked=False,
                processing_time_ms=round(elapsed_ms, 2),
            )

        stripped = text.strip()
        text_len = len(stripped)

        # 短文本过滤：embedding 对少于 8 字符的文本无区分度，直接跳过
        if text_len < 8:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            return ClassificationResult(
                category_scores={},
                triggered_categories=[],
                max_score=0.0,
                max_category=None,
                is_blocked=False,
                processing_time_ms=round(elapsed_ms, 2),
            )

        # 英文文本过滤：锚点全为中文，跨语言 embedding 相似度不可靠
        ascii_chars = sum(1 for c in stripped if ord(c) < 128 and c.isalpha())
        if ascii_chars / text_len > 0.5:
            elapsed_ms = (time.perf_counter() - start_time) * 1000
            return ClassificationResult(
                category_scores={},
                triggered_categories=[],
                max_score=0.0,
                max_category=None,
                is_blocked=False,
                processing_time_ms=round(elapsed_ms, 2),
            )

        # 逐类别计算最大相似度
        category_scores: Dict[str, float] = {}
        triggered: List[str] = []
        max_score = 0.0
        max_category = None

        for category in self._enabled_categories:
            anchor_embeddings = self._anchor_embeddings.get(category, [])
            if not anchor_embeddings:
                continue

            scores = [cosine_similarity(input_embedding, anchor) for anchor in anchor_embeddings]
            category_score = max(scores) if scores else 0.0
            category_scores[category] = round(category_score, 4)

            threshold = self._thresholds.get(category, 0.75)
            # 阈值下调 0.05，减少中长中文文本的漏报
            # threshold = threshold - 0.05

            if category_score >= threshold:
                triggered.append(category)

            if category_score > max_score:
                max_score = category_score
                max_category = category

        is_blocked = len(triggered) > 0 and self.action_mode == "block"
        elapsed_ms = (time.perf_counter() - start_time) * 1000
        return ClassificationResult(
            category_scores=category_scores,
            triggered_categories=triggered,
            max_score=round(max_score, 4),
            max_category=max_category,
            is_blocked=is_blocked,
            processing_time_ms=round(elapsed_ms, 2),
        )


# ---------------------------------------------------------------------- #
# 单例工厂
# ---------------------------------------------------------------------- #

_classifier_service_instance: Optional[ContentFilterService] = None


def get_classifier_service(
    input_enabled: bool = True,
    output_enabled: bool = True,
    action_mode: str = "block",
) -> ContentFilterService:
    """获取或创建单例分类服务。"""
    global _classifier_service_instance
    if _classifier_service_instance is None:
        _classifier_service_instance = ContentFilterService(
            input_enabled=input_enabled,
            output_enabled=output_enabled,
            action_mode=action_mode,
        )
    return _classifier_service_instance


def reset_classifier_service() -> None:
    """重置单例（用于测试或配置完全变更时）。"""
    global _classifier_service_instance
    _classifier_service_instance = None
