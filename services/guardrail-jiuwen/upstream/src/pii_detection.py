"""
PII检测服务层，为Agent和API提供统一的PII检测接口。

本模块封装了PII检测的核心逻辑，提供：
- 输入文本PII检测与脱敏
- 输出文本PII检测与脱敏
- PII检测统计与日志记录
- 与GuardRails AI的集成接口
"""

import logging
from typing import Dict, Any, List, Optional

from src.validators.pii_validator import (
    PIIDetector,
    PIIEntity,
    PIIDetectionResult,
    pii_detect,
    pii_anonymize,
    GUARDRAILS_INTEGRATION_AVAILABLE,
)

logger = logging.getLogger("agent_service")
security_logger = logging.getLogger("security")


class PIIDetectionService:
    """PII检测服务，管理检测器实例和检测策略。"""

    def __init__(
        self,
        input_entities: Optional[List[str]] = None,
        output_entities: Optional[List[str]] = None,
        use_presidio: bool = True,
        threshold: float = 0.0,
        anonymize_input: bool = True,
        anonymize_output: bool = True,
        action_mode: str = "detect",
        input_action_mode: Optional[str] = None,
        output_action_mode: Optional[str] = None,
        input_enabled: bool = True,
        output_enabled: bool = True,
    ):
        self.input_entities = input_entities if input_entities is not None else []
        self.output_entities = output_entities if output_entities is not None else []
        self.use_presidio = use_presidio
        self.threshold = threshold
        self.anonymize_input = anonymize_input
        self.anonymize_output = anonymize_output
        # 输入/输出独立的action_mode，未指定时回退到全局action_mode
        self.input_action_mode = input_action_mode if input_action_mode is not None else action_mode
        self.output_action_mode = output_action_mode if output_action_mode is not None else action_mode
        # 输入/输出独立的使能开关
        self.input_enabled = input_enabled
        self.output_enabled = output_enabled
        self.enabled = (self.input_enabled and len(self.input_entities) > 0) or (self.output_enabled and len(self.output_entities) > 0)

        self._input_detector: Optional[PIIDetector] = None
        self._output_detector: Optional[PIIDetector] = None

        self._rebuild_detectors()

    def _rebuild_detectors(self) -> None:
        """根据当前配置重建检测器实例。"""
        if self.input_enabled and self.input_entities:
            self._input_detector = PIIDetector(
                pii_entities=self.input_entities,
                use_presidio=self.use_presidio,
                threshold=self.threshold,
                name="input_detector",
            )
        else:
            self._input_detector = None

        if self.output_enabled and self.output_entities:
            self._output_detector = PIIDetector(
                pii_entities=self.output_entities,
                use_presidio=self.use_presidio,
                threshold=self.threshold,
                name="output_detector",
            )
        else:
            self._output_detector = None

        self.enabled = self._input_detector is not None or self._output_detector is not None

        logger.info(
            f"PIIDetectionService配置更新: "
            f"enabled={self.enabled}, "
            f"input_enabled={self.input_enabled}, output_enabled={self.output_enabled}, "
            f"input_entities={self.input_entities}, "
            f"output_entities={self.output_entities}, "
            f"input_action_mode={self.input_action_mode}, "
            f"output_action_mode={self.output_action_mode}"
        )

    def update_config(
        self,
        input_entities: Optional[List[str]] = None,
        output_entities: Optional[List[str]] = None,
        use_presidio: Optional[bool] = None,
        threshold: Optional[float] = None,
        anonymize_input: Optional[bool] = None,
        anonymize_output: Optional[bool] = None,
        action_mode: Optional[str] = None,
        input_action_mode: Optional[str] = None,
        output_action_mode: Optional[str] = None,
        input_enabled: Optional[bool] = None,
        output_enabled: Optional[bool] = None,
    ) -> None:
        """动态更新PII检测配置并重建检测器。"""
        if input_entities is not None:
            self.input_entities = input_entities
        if output_entities is not None:
            self.output_entities = output_entities
        if use_presidio is not None:
            self.use_presidio = use_presidio
        if threshold is not None:
            self.threshold = threshold
        if anonymize_input is not None:
            self.anonymize_input = anonymize_input
        if anonymize_output is not None:
            self.anonymize_output = anonymize_output
        if action_mode is not None:
            self.input_action_mode = action_mode
            self.output_action_mode = action_mode
        if input_action_mode is not None:
            self.input_action_mode = input_action_mode
        if output_action_mode is not None:
            self.output_action_mode = output_action_mode
        if input_enabled is not None:
            self.input_enabled = input_enabled
        if output_enabled is not None:
            self.output_enabled = output_enabled

        self._rebuild_detectors()

    def check_input(self, text: str) -> Dict[str, Any]:
        if not self.input_enabled or self._input_detector is None:
            return {
                "has_pii": False,
                "anonymized_text": text,
                "summary": {"has_pii": False, "total_count": 0, "entity_counts": {}, "entities": []},
                "original_text": text,
                "should_block": False,
            }

        results = self._input_detector.detect(text)
        has_pii = len(results) > 0
        should_block = has_pii and self.input_action_mode == "block"

        if has_pii:
            if should_block:
                security_logger.warning(
                    f"输入PII拦截: {len(results)}个实体, "
                    f"类型: {[r.entity_type for r in results]}, "
                    f"模式: {self.input_action_mode}"
                )
            else:
                security_logger.info(
                    f"输入检测到PII: {len(results)}个实体, "
                    f"类型: {[r.entity_type for r in results]}"
                )

        anonymized = self._input_detector.anonymize(text) if self.anonymize_input else text

        return {
            "has_pii": has_pii,
            "anonymized_text": anonymized,
            "summary": self._input_detector.get_pii_summary(text),
            "original_text": text,
            "should_block": should_block,
        }

    def check_output(self, text: str) -> Dict[str, Any]:
        if not self.output_enabled or self._output_detector is None:
            return {
                "has_pii": False,
                "anonymized_text": text,
                "summary": {"has_pii": False, "total_count": 0, "entity_counts": {}, "entities": []},
                "original_text": text,
                "should_block": False,
            }

        results = self._output_detector.detect(text)
        has_pii = len(results) > 0
        should_block = has_pii and self.output_action_mode == "block"

        if has_pii:
            if should_block:
                security_logger.warning(
                    f"输出PII拦截: {len(results)}个实体, "
                    f"类型: {[r.entity_type for r in results]}, "
                    f"模式: {self.output_action_mode}"
                )
            else:
                security_logger.warning(
                    f"输出检测到PII泄露: {len(results)}个实体, "
                    f"类型: {[r.entity_type for r in results]}"
                )

        anonymized = self._output_detector.anonymize(text) if self.anonymize_output else text

        return {
            "has_pii": has_pii,
            "anonymized_text": anonymized,
            "summary": self._output_detector.get_pii_summary(text),
            "original_text": text,
            "should_block": should_block,
        }

    def anonymize_text(self, text: str, entity_type: str = "all") -> str:
        detector = PIIDetector(
            pii_entities=[entity_type],
            use_presidio=self.use_presidio,
            threshold=self.threshold,
        )
        return detector.anonymize(text)

    def detect_text(self, text: str, entity_type: str = "all") -> List[PIIDetectionResult]:
        detector = PIIDetector(
            pii_entities=[entity_type],
            use_presidio=self.use_presidio,
            threshold=self.threshold,
        )
        return detector.detect(text)


_pii_service_instance: Optional[PIIDetectionService] = None


def get_pii_service(
    input_entities: Optional[List[str]] = None,
    output_entities: Optional[List[str]] = None,
    use_presidio: bool = True,
    threshold: float = 0.0,
    anonymize_input: bool = True,
    anonymize_output: bool = True,
    action_mode: str = "detect",
    input_action_mode: Optional[str] = None,
    output_action_mode: Optional[str] = None,
    input_enabled: bool = True,
    output_enabled: bool = True,
) -> PIIDetectionService:
    global _pii_service_instance
    if _pii_service_instance is None:
        _pii_service_instance = PIIDetectionService(
            input_entities=input_entities,
            output_entities=output_entities,
            use_presidio=use_presidio,
            threshold=threshold,
            anonymize_input=anonymize_input,
            anonymize_output=anonymize_output,
            action_mode=action_mode,
            input_action_mode=input_action_mode,
            output_action_mode=output_action_mode,
            input_enabled=input_enabled,
            output_enabled=output_enabled,
        )
    return _pii_service_instance


def reset_pii_service() -> None:
    global _pii_service_instance
    _pii_service_instance = None
