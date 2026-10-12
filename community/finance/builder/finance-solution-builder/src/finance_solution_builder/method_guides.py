"""Reviewed reading aids, not executable translations or model-generated instructions."""
from hashlib import sha256
from pathlib import Path


GUIDES = {
    "finance.cashflow.readonly": {
        "version": "1.0.2", "label": "收支分析", "groupNote": "两项能力成组装配，按问题分别调用",
        "steps": ["确认查询月份；相对月份需有可信业务日期，否则先询问。",
                  "按需查询收支汇总或分类趋势；完整分析先摘要再分类趋势。趋势按来源六个月政策，问候不触发查询。",
                  "分别说明金额、币种、范围、来源和时间；不同口径不强行相加或对齐。",
                  "列出未覆盖、时区未核实或趋势不可比等缺口；零值不等于缺失，不推断欺诈或适当性。"],
        "sources": [("repo", "domain-packs/cashflow/customer-self-service/skills/cashflow-review/SKILL.md",
                     "4c7e01b2206f24c9fca8ea649121623525b0709e1c51636f06c19a86cfd945e9", "0.1.3")],
    },
    "account.balance.query": {
        "version": "1.0.1", "label": "余额查询",
        "steps": ["查询本人余额时直接读取获授权账户，无需先索取账户编号。",
                  "仅在已有可信账户结果或明确有效选择时按账户筛选。",
                  "说明可用余额、币种、来源与时间；不混加币种，不当作财富总额。"],
        "sources": [("builder", "templates/local-readonly-service/methods/account-balance.md",
                     "2aea7d374467ea1eebd3107e53f687ef71fb76915cca79826c590eef8b0f1750", "0.1.1")],
    },
    "wealth.facts.describe": {
        "version": "1.0.1", "label": "财富概览",
        "steps": ["仅对资产、测评和信息缺口问题读取财富事实；问候和仅查余额不触发。",
                  "先说明已知资产的金额、币种、统计范围及估值时间，不升级为家庭总资产。",
                  "保留风险测评体系和等级，说明过期或缺项，不换算等级或推断适当性。",
                  "区分零值、缺失、不可用、未覆盖和冲突；未授权不描述客户事实，不作正式推荐。"],
        "sources": [("builder", "templates/local-readonly-service/methods/wealth-facts.md",
                     "7edc8297b27bd1d4755e18a2d01eaa54f88eb457dc14603374b4eb4299841a0f", "0.1.1"),
                    ("repo", "domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md",
                     "2361139ee83100b5ce68c65cc706c435f3d15a7112e214355c41bfdf466a322b", "0.1.1")],
    },
}


def describe_guide(component_id, harness, project=None):
    guide = GUIDES[component_id]
    project = project or Path(__file__).resolve().parents[2]
    sources = []
    for kind, path, digest, version in guide["sources"]:
        root = harness if kind == "repo" else project
        try:
            matched = root is not None and sha256((root / path).read_bytes()).hexdigest() == digest
        except OSError:
            matched = False
        sources.append({"source": kind + "://" + path, "version": version,
                        "digest": "sha256:" + digest, "matches": matched})
    current = all(s["matches"] for s in sources)
    return {"version": guide["version"], "label": guide["label"],
            "groupNote": guide.get("groupNote"), "sources": sources,
            "status": "SOURCE_MATCHED" if current else "REVIEW_REQUIRED",
            "steps": guide["steps"] if current else [],
            "kind": "READING_GUIDE_NOT_EXECUTABLE_TRANSLATION"}
