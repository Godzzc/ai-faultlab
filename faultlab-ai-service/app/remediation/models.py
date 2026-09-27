from enum import StrEnum
from typing import Any
from uuid import uuid4

from pydantic import Field

from app.schemas import CamelModel


class RemediationPlanStatus(StrEnum):
    PROPOSED = "PROPOSED"
    UNSUPPORTED = "UNSUPPORTED"
    INVALID = "INVALID"


class ExpectedMetricDirection(StrEnum):
    INCREASE = "INCREASE"
    DECREASE = "DECREASE"
    STABLE = "STABLE"


class ExpectedMetricEffect(CamelModel):
    metric_name: str
    direction: ExpectedMetricDirection
    description: str = ""


class RemediationAction(CamelModel):
    action_type: str
    description: str = ""
    parameter_patch: dict[str, Any] = Field(default_factory=dict)
    rationale: str = ""


class RemediationPlan(CamelModel):
    plan_id: str = Field(default_factory=lambda: str(uuid4()))
    experiment_id: str | None = None
    scenario_code: str = "UNKNOWN"
    actions: list[RemediationAction] = Field(default_factory=list)
    expected_effects: list[ExpectedMetricEffect] = Field(default_factory=list)
    evidence: list[str] = Field(default_factory=list)
    confidence: float = 0.0
    status: RemediationPlanStatus = RemediationPlanStatus.PROPOSED
    warnings: list[str] = Field(default_factory=list)
