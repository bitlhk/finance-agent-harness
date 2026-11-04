"""
Embedding-based content classifier for Finance Guardrail.

通过 httpx 直接调用 SiliconFlow Embedding API，
实现零样本内容分类（无需本地模型文件，不依赖 LangChain）。
"""
import logging
from typing import Dict, List, Optional

import httpx

from config.config import OPENAI_API_KEY, EMBEDDING_BASE_URL, EMBEDDING_MODEL

logger = logging.getLogger("agent_service")


async def fetch_embeddings(texts: List[str], model: Optional[str] = None) -> Optional[List[List[float]]]:
    """
    通过 SiliconFlow API 批量获取文本的 Embedding 向量。

    使用 httpx 直接调用 OpenAI 兼容的 Embedding API，不依赖 langchain。

    Args:
        texts: 待编码的文本列表（单条或多条）
        model: 模型名称（保留参数以兼容旧接口，实际使用初始化时的模型）

    Returns:
        每个文本对应的 embedding 向量列表，失败返回 None
    """
    if not OPENAI_API_KEY:
        logger.warning("OPENAI_API_KEY 未配置，无法获取 embedding")
        return None

    if not EMBEDDING_BASE_URL or not EMBEDDING_MODEL:
        logger.warning("EMBEDDING_BASE_URL 或 EMBEDDING_MODEL 未配置，无法获取 embedding")
        return None

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                f"{EMBEDDING_BASE_URL}/embeddings",
                headers={
                    "Authorization": f"Bearer {OPENAI_API_KEY}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": EMBEDDING_MODEL,
                    "input": texts,
                },
            )
            resp.raise_for_status()
            data = resp.json()

        vectors = [item["embedding"] for item in data["data"]]

        logger.debug(
            f"Embedding API 成功: model={EMBEDDING_MODEL}, "
            f"texts={len(texts)}, dims={len(vectors[0]) if vectors else 0}"
        )
        return vectors

    except Exception as e:
        logger.error(f"Embedding API 调用失败: {e}", exc_info=True)
        return None


async def fetch_embedding(text: str, model: Optional[str] = None) -> Optional[List[float]]:
    """获取单条文本的 embedding（fetch_embeddings 的便捷封装）。"""
    vectors = await fetch_embeddings([text], model)
    return vectors[0] if vectors else None


def cosine_similarity(a: List[float], b: List[float]) -> float:
    """
    计算两个归一化向量的余弦相似度。

    若向量未归一化，结果会大于 1 或小于 -1，调用方应自行 clamp。
    """
    dot = sum(x * y for x, y in zip(a, b))
    norm_a = sum(x * x for x in a) ** 0.5
    norm_b = sum(x * x for x in b) ** 0.5
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return max(-1.0, min(1.0, dot / (norm_a * norm_b)))
