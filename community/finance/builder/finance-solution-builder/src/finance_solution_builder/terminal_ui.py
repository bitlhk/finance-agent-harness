"""Scenario-owned presentation contract; never an identity or capability grant."""
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator


class InterfaceConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)
    schemaVersion: Literal["finance-interface.v0.1"] = "finance-interface.v0.1"
    assistantName: str = Field(default="我的金融助手", min_length=1, max_length=24)
    brandIcon: Literal["landmark", "blocks", "shield-check"] = "landmark"
    theme: Literal["FINANCE_RED", "EXAMPLE_TEAL"] = "FINANCE_RED"
    balanceCard: Literal["balance-summary-v1", "balance-ledger-v1"] = "balance-summary-v1"
    fontSize: Literal["STANDARD", "LARGE"] = "STANDARD"
    shortcuts: list[Literal["BALANCE", "WEALTH", "CASHFLOW_SUMMARY", "CASHFLOW_ANALYSIS"]] = Field(
        default_factory=lambda: ["BALANCE", "WEALTH", "CASHFLOW_SUMMARY", "CASHFLOW_ANALYSIS"], max_length=4)
    voiceInput: bool = False
    sourcePanelExpanded: bool = True
    layoutVersion: Literal["terminal-layout.v0.1"] = "terminal-layout.v0.1"

    @field_validator("assistantName")
    @classmethod
    def safe_name(cls, value):
        if value != value.strip() or any(ord(c) < 32 or c in "<>" for c in value):
            raise ValueError("DISPLAY_NAME_INVALID")
        return value

    @field_validator("shortcuts")
    @classmethod
    def unique(cls, value):
        if len(value) != len(set(value)):
            raise ValueError("SHORTCUT_DUPLICATE")
        return value


TERMINALS = [
    {"id": "MOBILE_WEB", "label": "手机端", "icon": "smartphone"},
    {"id": "DESKTOP_WEB", "label": "电脑端", "icon": "monitor"},
    {"id": "ATM_WEB", "label": "ATM端", "icon": "landmark"},
]


def presentation_config(selection):
    terminal = selection.get("presentation", "MOBILE_WEB")
    if terminal == "RESPONSIVE_WEB":
        terminal = "MOBILE_WEB"
    if terminal not in {t["id"] for t in TERMINALS}:
        raise ValueError("TERMINAL_UNSUPPORTED")
    config = InterfaceConfig.model_validate(selection.get("interfaceConfig", {})).model_dump()
    if terminal == "ATM_WEB" and config["voiceInput"]:
        raise ValueError("ATM_VOICE_UNSUPPORTED")
    return {**config, "terminal": terminal,
            "sessionPolicy": {"idleSeconds": 120, "warningSeconds": 15, "absoluteSeconds": 600}
            if terminal == "ATM_WEB" else {"idleSeconds": 900, "warningSeconds": 30, "absoluteSeconds": 3600}}
