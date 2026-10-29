"""
自定义PII（个人身份信息）检测Validator，支持GuardRails AI集成。

本模块实现了针对中国金融场景的PII检测方案：
- 支持中国特有PII：身份证号、手机号、银行卡号、中文姓名等
- 集成Microsoft Presidio进行英文PII检测（可选依赖）
- 支持GuardRails AI框架（register_validator）
- 提供脱敏/匿名化功能

参考GuardRails AI detect_pii方案实现：
https://guardrailsai.com/hub/validator/guardrails/detect_pii
"""

import re
import logging
from enum import Enum
from typing import Dict, Any, List, Optional, Tuple, Pattern
from dataclasses import dataclass

from config import SPACY_PATH, SPACY_EN_PATH

logger = logging.getLogger("agent_service")


def _ensure_str(text: Any) -> str:
    """将可能的 LangChain 包装对象（AIMessage/TextAccessor等）解包为纯字符串。

    TextAccessor 继承自 str，isinstance 会误判，因此使用 type() 精确判断。
    """
    while hasattr(text, "content"):
        text = text.content
    if type(text) is not str:
        text = str(text)
    return text


class PIIEntity(str, Enum):
    """支持的PII实体类型。"""

    # 标准PII（英文场景，对应Presidio）
    EMAIL_ADDRESS = "EMAIL_ADDRESS"
    PHONE_NUMBER = "PHONE_NUMBER"
    DOMAIN_NAME = "DOMAIN_NAME"
    IP_ADDRESS = "IP_ADDRESS"
    DATE_TIME = "DATE_TIME"
    LOCATION = "LOCATION"
    PERSON = "PERSON"
    URL = "URL"

    # 敏感个人信息（英文场景，对应Presidio）
    CREDIT_CARD = "CREDIT_CARD"
    CRYPTO = "CRYPTO"
    IBAN_CODE = "IBAN_CODE"
    NRP = "NRP"
    MEDICAL_LICENSE = "MEDICAL_LICENSE"
    US_BANK_NUMBER = "US_BANK_NUMBER"
    US_DRIVER_LICENSE = "US_DRIVER_LICENSE"
    US_ITIN = "US_ITIN"
    US_PASSPORT = "US_PASSPORT"
    US_SSN = "US_SSN"

    # 中国金融场景PII（自定义正则）
    CN_ID_CARD = "CN_ID_CARD"
    CN_PHONE = "CN_PHONE"
    CN_BANK_CARD = "CN_BANK_CARD"
    CN_NAME = "CN_NAME"
    CN_ADDRESS = "CN_ADDRESS"
    CN_PASSPORT = "CN_PASSPORT"

    # 分类别名
    PII = "pii"
    SPI = "spi"
    CN_PII = "cn_pii"
    ALL = "all"


PII_CATEGORY_MAP: Dict[str, List[str]] = {
    PIIEntity.PII: [
        PIIEntity.EMAIL_ADDRESS.value,
        PIIEntity.PHONE_NUMBER.value,
        PIIEntity.DOMAIN_NAME.value,
        PIIEntity.IP_ADDRESS.value,
        PIIEntity.DATE_TIME.value,
        PIIEntity.LOCATION.value,
        PIIEntity.PERSON.value,
        PIIEntity.URL.value,
    ],
    PIIEntity.SPI: [
        PIIEntity.CREDIT_CARD.value,
        PIIEntity.CRYPTO.value,
        PIIEntity.IBAN_CODE.value,
        PIIEntity.NRP.value,
        PIIEntity.MEDICAL_LICENSE.value,
        PIIEntity.US_BANK_NUMBER.value,
        PIIEntity.US_DRIVER_LICENSE.value,
        PIIEntity.US_ITIN.value,
        PIIEntity.US_PASSPORT.value,
        PIIEntity.US_SSN.value,
    ],
    PIIEntity.CN_PII: [
        PIIEntity.CN_ID_CARD.value,
        PIIEntity.CN_PHONE.value,
        PIIEntity.CN_BANK_CARD.value,
        PIIEntity.CN_NAME.value,
        PIIEntity.CN_ADDRESS.value,
        PIIEntity.CN_PASSPORT.value,
    ],
    PIIEntity.ALL: [],
}


def _build_all_entities() -> List[str]:
    all_entities = set()
    for category in [PIIEntity.PII, PIIEntity.SPI, PIIEntity.CN_PII]:
        all_entities.update(PII_CATEGORY_MAP[category])
    return sorted(list(all_entities))


# 实体检测优先级（高优先级优先检测，避免宽泛模式覆盖具体模式）
ENTITY_PRIORITY: Dict[str, int] = {
    PIIEntity.CN_ID_CARD.value: 100,
    PIIEntity.CN_PASSPORT.value: 90,
    PIIEntity.CN_PHONE.value: 80,
    PIIEntity.EMAIL_ADDRESS.value: 70,
    PIIEntity.CREDIT_CARD.value: 65,
    PIIEntity.CN_BANK_CARD.value: 60,
    PIIEntity.CN_NAME.value: 50,
    PIIEntity.PERSON.value: 45,
    PIIEntity.CN_ADDRESS.value: 40,
    PIIEntity.US_SSN.value: 35,
    PIIEntity.US_PASSPORT.value: 30,
    PIIEntity.US_DRIVER_LICENSE.value: 25,
    PIIEntity.PHONE_NUMBER.value: 20,
    PIIEntity.IP_ADDRESS.value: 15,
    PIIEntity.URL.value: 10,
    PIIEntity.DOMAIN_NAME.value: 5,
    # 其他默认为 0
}


PII_CATEGORY_MAP[PIIEntity.ALL] = _build_all_entities()


@dataclass
class PIIDetectionResult:
    """PII检测结果。"""
    entity_type: str
    start: int
    end: int
    text: str
    score: float = 1.0


CN_PII_PATTERNS: Dict[str, Tuple[Pattern, str]] = {
    PIIEntity.CN_ID_CARD.value: (
        re.compile(
            r"(?<![0-9A-Za-z])"
            r"([1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])"
            r"\d{3}[\dXx])"
            r"(?![0-9A-Za-z])"
        ),
        "<CN_ID_CARD>"
    ),
    PIIEntity.CN_PHONE.value: (
        re.compile(
            r"(?<![0-9])"
            r"(1[3-9]\d{9})"
            r"(?![0-9])"
        ),
        "<CN_PHONE>"
    ),
    PIIEntity.CN_BANK_CARD.value: (
        re.compile(
            r"(?<![0-9])"
            r"(\d{16,19})"
            r"(?![0-9])"
        ),
        "<CN_BANK_CARD>"
    ),
    PIIEntity.CN_PASSPORT.value: (
        re.compile(
            r"(?<![0-9A-Za-z])"
            r"([Ee][\dA-Za-z]\d{7,8})"
            r"(?![0-9A-Za-z])"
        ),
        "<CN_PASSPORT>"
    ),
}

EMAIL_PATTERN = (
    re.compile(
        r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"
    ),
    "<EMAIL_ADDRESS>"
)

# 常见日期格式正则（作为 Presidio DATE_TIME 的补充）
DATE_TIME_PATTERNS: List[Tuple[Pattern, str]] = [
    # YYYY-MM-DD / YYYY/MM/DD
    (re.compile(r"(?<![0-9])(\d{4}[-/]\d{1,2}[-/]\d{1,2})(?![0-9])"), "DATE_TIME"),
    # DD/MM/YYYY / MM/DD/YYYY
    (re.compile(r"(?<![0-9])(\d{1,2}[-/]\d{1,2}[-/]\d{4})(?![0-9])"), "DATE_TIME"),
]

def _detect_language_for_presidio(text: str) -> str:
    """根据文本内容判断应使用的 Presidio 语言。"""
    for char in text:
        if "一" <= char <= "鿿":
            return "zh"
    return "en"


PRESIDIO_AVAILABLE = False
PRESIDIO_ANALYZER = None
PRESIDIO_ANONYMIZER = None

try:
    from presidio_analyzer import AnalyzerEngine
    from presidio_analyzer.nlp_engine import NlpEngineProvider
    from presidio_anonymizer import AnonymizerEngine
    from presidio_anonymizer.entities import OperatorConfig

    PRESIDIO_AVAILABLE = True
    logger.info("Presidio已加载，英文PII检测可用")
except ImportError:
    logger.info("Presidio未安装，仅使用正则表达式进行PII检测")


PRESIDIO_ENTITY_MAP: Dict[str, str] = {
    PIIEntity.EMAIL_ADDRESS: "EMAIL_ADDRESS",
    PIIEntity.PHONE_NUMBER: "PHONE_NUMBER",
    PIIEntity.DOMAIN_NAME: "DOMAIN_NAME",
    PIIEntity.IP_ADDRESS: "IP_ADDRESS",
    PIIEntity.DATE_TIME: "DATE_TIME",
    PIIEntity.LOCATION: "LOCATION",
    PIIEntity.PERSON: "PERSON",
    PIIEntity.URL: "URL",
    PIIEntity.CREDIT_CARD: "CREDIT_CARD",
    PIIEntity.CRYPTO: "CRYPTO",
    PIIEntity.IBAN_CODE: "IBAN_CODE",
    PIIEntity.NRP: "NRP",
    PIIEntity.MEDICAL_LICENSE: "MEDICAL_LICENSE",
    PIIEntity.US_BANK_NUMBER: "US_BANK_NUMBER",
    PIIEntity.US_DRIVER_LICENSE: "US_DRIVER_LICENSE",
    PIIEntity.US_ITIN: "US_ITIN",
    PIIEntity.US_PASSPORT: "US_PASSPORT",
    PIIEntity.US_SSN: "US_SSN",
}


def _get_presidio_analyzer() -> Optional[Any]:
    global PRESIDIO_ANALYZER
    if not PRESIDIO_AVAILABLE:
        return None
    if PRESIDIO_ANALYZER is None:
        try:
            nlp_configuration = {
                "nlp_engine_name": "spacy",
                "models": [
                    {
                        "lang_code": "zh",
                        "model_name": SPACY_PATH.as_posix()
                    },
                    {
                        "lang_code": "en",
                        "model_name": SPACY_EN_PATH.as_posix()
                    }
                ],
                "ner_model_configuration": {
                    "labels_to_ignore": ["CARDINAL"],
                },
            }
            provider = NlpEngineProvider(nlp_configuration=nlp_configuration)
            nlp_engine = provider.create_engine()
            # Presidio 默认会加载所有语言的内置识别器（es/it/pl 等），
            # 这些识别器的语言与当前 registry 不匹配时会打印大量 WARNING。
            # 同时 spaCy NER 识别的 CARDINAL 等标签未映射到 Presidio 实体时也会打印 WARNING。
            # 将 presidio-analyzer 日志级别提升到 ERROR，抑制这些无意义的警告。
            logging.getLogger("presidio-analyzer").setLevel(logging.ERROR)
            PRESIDIO_ANALYZER = AnalyzerEngine(nlp_engine=nlp_engine)
        except Exception as e:
            logger.error(f"初始化Presidio Analyzer失败: {e}")
            return None
    return PRESIDIO_ANALYZER


def _get_presidio_anonymizer() -> Optional[Any]:
    global PRESIDIO_ANONYMIZER
    if not PRESIDIO_AVAILABLE:
        return None
    if PRESIDIO_ANONYMIZER is None:
        try:
            PRESIDIO_ANONYMIZER = AnonymizerEngine()
        except Exception as e:
            logger.error(f"初始化Presidio Anonymizer失败: {e}")
            return None
    return PRESIDIO_ANONYMIZER


class PIIDetector:
    """PII检测器，支持正则和Presidio两种检测方式。"""

    def __init__(
        self,
        pii_entities: Optional[List[str]] = None,
        use_presidio: bool = True,
        threshold: float = 0.0,
        name: str = "default",
    ):
        self.name = name
        self.use_presidio = use_presidio and PRESIDIO_AVAILABLE
        self.threshold = threshold
        self.entities = self._resolve_entities(pii_entities or [PIIEntity.ALL])
        # 按优先级排序，确保具体模式优先于宽泛模式
        self.entities = sorted(
            self.entities,
            key=lambda e: -ENTITY_PRIORITY.get(e, 0)
        )
        self._presidio_analyzer = None
        self._presidio_anonymizer = None

        if self.use_presidio:
            self._presidio_analyzer = _get_presidio_analyzer()
            self._presidio_anonymizer = _get_presidio_anonymizer()
            if self._presidio_analyzer is None:
                self.use_presidio = False

        logger.info(
            f"PIIDetector初始化完成: name={self.name}, "
            f"entities={self.entities}, use_presidio={self.use_presidio}"
        )

    def _resolve_entities(self, entities: List[str]) -> List[str]:
        resolved = set()
        for entity in entities:
            # 处理Enum对象：优先使用.value，否则用str
            if isinstance(entity, Enum):
                entity_str = entity.value
            else:
                entity_str = str(entity)
            # 尝试多种大小写形式匹配类别别名
            matched = False
            for key in [entity_str, entity_str.lower(), entity_str.upper()]:
                if key in PII_CATEGORY_MAP:
                    resolved.update(PII_CATEGORY_MAP[key])
                    matched = True
                    break
            if not matched:
                resolved.add(entity_str.upper())
        return sorted(list(resolved))

    def detect(self, text: str) -> List[PIIDetectionResult]:
        text = _ensure_str(text)
        results: List[PIIDetectionResult] = []
        results.extend(self._detect_with_regex(text))

        if self.use_presidio:
            results.extend(self._detect_with_presidio(text))

        results = self._deduplicate_results(results)
        results.sort(key=lambda x: x.start)
        return results

    def _detect_with_regex(self, text: str) -> List[PIIDetectionResult]:
        results = []

        # 当选择 PHONE_NUMBER 时，也尝试用 CN_PHONE 正则检测中国手机号
        regex_entities = set(self.entities)
        if PIIEntity.PHONE_NUMBER.value in regex_entities:
            regex_entities.add(PIIEntity.CN_PHONE.value)

        for entity_type in regex_entities:
            if entity_type in CN_PII_PATTERNS:
                pattern, _ = CN_PII_PATTERNS[entity_type]
                for match in pattern.finditer(text):
                    if match.lastindex and match.lastindex >= 1:
                        group_start = match.start(1)
                        group_end = match.end(1)
                        matched_text = match.group(1)
                    else:
                        group_start = match.start()
                        group_end = match.end()
                        matched_text = match.group()

                    # PHONE_NUMBER 检测到中国手机号时，统一上报为 PHONE_NUMBER
                    report_type = entity_type
                    if entity_type == PIIEntity.CN_PHONE.value and PIIEntity.PHONE_NUMBER.value in self.entities:
                        report_type = PIIEntity.PHONE_NUMBER.value

                    results.append(PIIDetectionResult(
                        entity_type=report_type,
                        start=group_start,
                        end=group_end,
                        text=matched_text,
                        score=1.0,
                    ))

        if PIIEntity.EMAIL_ADDRESS.value in self.entities:
            pattern, _ = EMAIL_PATTERN
            for match in pattern.finditer(text):
                results.append(PIIDetectionResult(
                    entity_type=PIIEntity.EMAIL_ADDRESS.value,
                    start=match.start(),
                    end=match.end(),
                    text=match.group(),
                    score=1.0,
                ))

        # DATE_TIME 正则检测（弥补 Presidio 中文模型对日期检测的不足）
        if PIIEntity.DATE_TIME.value in self.entities:
            for pattern, _ in DATE_TIME_PATTERNS:
                for match in pattern.finditer(text):
                    results.append(PIIDetectionResult(
                        entity_type=PIIEntity.DATE_TIME.value,
                        start=match.start(),
                        end=match.end(),
                        text=match.group(),
                        score=1.0,
                    ))

        return results

    def _detect_with_presidio(self, text: str) -> List[PIIDetectionResult]:
        results = []
        if self._presidio_analyzer is None:
            return results

        presidio_entities = []
        for entity in self.entities:
            if entity in PRESIDIO_ENTITY_MAP:
                presidio_entities.append(PRESIDIO_ENTITY_MAP[entity])

        if not presidio_entities:
            return results

        try:
            language = _detect_language_for_presidio(text)
            analyzer_results = self._presidio_analyzer.analyze(
                text=text,
                language=language,
                entities=presidio_entities,
            )

            for result in analyzer_results:
                if result.score >= self.threshold:
                    results.append(PIIDetectionResult(
                        entity_type=result.entity_type,
                        start=result.start,
                        end=result.end,
                        text=text[result.start:result.end],
                        score=result.score,
                    ))
        except Exception as e:
            err_msg = str(e)
            if "No matching recognizers" in err_msg:
                logger.debug(f"Presidio在en语言下无匹配识别器，跳过: {err_msg}")
            else:
                logger.error(f"Presidio检测失败: {e}")

        return results

    def _deduplicate_results(
        self, results: List[PIIDetectionResult]
    ) -> List[PIIDetectionResult]:
        if not results:
            return results

        sorted_results = sorted(results, key=lambda r: (r.start, -(r.end - r.start)))
        filtered = []

        for result in sorted_results:
            overlap = False
            for kept in filtered:
                if result.start >= kept.start and result.end <= kept.end:
                    overlap = True
                    break
                if not (result.end <= kept.start or result.start >= kept.end):
                    if (result.end - result.start) <= (kept.end - kept.start):
                        overlap = True
                        break

            if not overlap:
                filtered.append(result)

        return filtered

    def anonymize(self, text: str) -> str:
        text = _ensure_str(text)
        # 统一使用正则脱敏：基于 detect 的完整结果（Presidio + 正则），
        # 且严格限定只替换用户选择的实体类型，避免 Presidio 分析时
        # 不限定 entities 导致误替换未选中的实体。
        return self._anonymize_with_regex(text)

    def _anonymize_with_presidio(self, text: str) -> str:
        try:
            language = _detect_language_for_presidio(text)
            analyzer_results = self._presidio_analyzer.analyze(
                text=text,
                language=language,
            )
        except Exception as e:
            err_msg = str(e)
            if "No matching recognizers" in err_msg:
                logger.debug(f"Presidio在{language}语言下无匹配识别器，跳过匿名化: {err_msg}")
            else:
                logger.warning(f"Presidio匿名化分析失败: {e}")
            return text

        analyzer_results = [r for r in analyzer_results if r.score >= self.threshold]

        if not analyzer_results:
            return text

        operators = {}
        for result in analyzer_results:
            entity_key = result.entity_type.lower()
            operators[entity_key] = OperatorConfig(
                "replace",
                {"new_value": f"<{result.entity_type}>"}
            )

        anonymized = self._presidio_anonymizer.anonymize(
            text=text,
            analyzer_results=analyzer_results,
            operators=operators,
        )
        return anonymized.text

    def _anonymize_with_regex(self, text: str) -> str:
        results = self.detect(text)
        results.sort(key=lambda r: r.start, reverse=True)

        anonymized = text
        for result in results:
            replacement = f"<{result.entity_type}>"
            anonymized = (
                anonymized[:result.start] +
                replacement +
                anonymized[result.end:]
            )

        return anonymized

    def has_pii(self, text: str) -> bool:
        text = _ensure_str(text)
        return len(self.detect(text)) > 0

    def get_pii_summary(self, text: str) -> Dict[str, Any]:
        text = _ensure_str(text)
        results = self.detect(text)
        entity_counts: Dict[str, int] = {}
        for r in results:
            entity_counts[r.entity_type] = entity_counts.get(r.entity_type, 0) + 1

        return {
            "has_pii": len(results) > 0,
            "total_count": len(results),
            "entity_counts": entity_counts,
            "entities": [
                {
                    "type": r.entity_type,
                    "text": r.text[:20] + "..." if len(r.text) > 20 else r.text,
                    "position": (r.start, r.end),
                    "score": r.score,
                }
                for r in results
            ],
        }


def pii_detect(
    text: str,
    pii_entities: Optional[List[str]] = None,
    use_presidio: bool = True,
) -> List[PIIDetectionResult]:
    detector = PIIDetector(pii_entities=pii_entities, use_presidio=use_presidio)
    return detector.detect(text)


def pii_anonymize(
    text: str,
    pii_entities: Optional[List[str]] = None,
    use_presidio: bool = True,
) -> str:
    detector = PIIDetector(pii_entities=pii_entities, use_presidio=use_presidio)
    return detector.anonymize(text)


try:
    from guardrails.validator_base import (
        Validator,
        register_validator,
        FailResult,
        PassResult,
    )

    @register_validator(name="custom_detect_pii", data_type="string")
    class CustomPIIValidator(Validator):
        """自定义PII检测Validator，支持GuardRails AI框架集成。

        Usage:
            guard = Guard().use(
                CustomPIIValidator,
                pii_entities=["CN_ID_CARD", "CN_PHONE", "EMAIL_ADDRESS"],
                on_fail="fix",
            )
            result = guard.validate("我的手机号是13800138000")
        """

        def __init__(
            self,
            pii_entities: Optional[List[str]] = None,
            use_presidio: bool = True,
            threshold: float = 0.0,
            on_fail: Optional[str] = None,
            **kwargs,
        ):
            super().__init__(on_fail=on_fail, **kwargs)
            self.detector = PIIDetector(
                pii_entities=pii_entities,
                use_presidio=use_presidio,
                threshold=threshold,
            )
            self.pii_entities = pii_entities
            self.use_presidio = use_presidio

        def _validate(self, value: str, metadata: Dict[str, Any]):
            results = self.detector.detect(value)

            if not results:
                return PassResult()

            entity_summary = {}
            for r in results:
                entity_summary[r.entity_type] = entity_summary.get(r.entity_type, 0) + 1

            entity_details = ", ".join(
                f"{k}({v})" for k, v in entity_summary.items()
            )
            error_msg = f"检测到PII: {entity_details}"

            anonymized = self.detector.anonymize(value)

            return FailResult(
                error_message=error_msg,
                fix_value=anonymized,
            )

    GUARDRAILS_INTEGRATION_AVAILABLE = True

except Exception as e:
    logger.error(f"GuardRails AI Validator基础类不可用: {e}")
    GUARDRAILS_INTEGRATION_AVAILABLE = False
    CustomPIIValidator = None  # type: ignore
    Validator = None  # type: ignore
    register_validator = None  # type: ignore
    FailResult = None  # type: ignore
    PassResult = None  # type: ignore
