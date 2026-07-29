from typing import Any

from pydantic import BaseModel, ConfigDict, Field


def to_camel(value: str) -> str:
    parts = value.split("_")
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
    )


class ExperimentInfo(CamelModel):
    experiment_id: str = ""
    scenario_code: str = ""
    status: str = ""
    trace_id: str = ""


class MetricItem(CamelModel):
    metric_name: str = ""
    metric_value: str = ""
    metric_unit: str = ""
    component: str = ""


class RuleResult(CamelModel):
    experiment_id: str = ""
    fault_type: str = ""
    fault_name: str = ""
    confidence: float = 0.0
    matched: bool = False
    reason: str = ""
    evidence: list[str] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)


class TraceNode(CamelModel):
    trace_id: str = ""
    span_id: str = ""
    parent_span_id: str | None = None
    experiment_id: str = ""
    operation_name: str = ""
    component: str = ""
    duration_ms: int = 0
    status: str = ""
    tags: dict[str, Any] = Field(default_factory=dict)
    children: list["TraceNode"] = Field(default_factory=list)


class TraceTree(CamelModel):
    trace_id: str = ""
    roots: list[TraceNode] = Field(default_factory=list)


class DiagnosisRequest(CamelModel):
    experiment: ExperimentInfo = Field(default_factory=ExperimentInfo)
    metrics: list[MetricItem] = Field(default_factory=list)
    trace_tree: TraceTree = Field(default_factory=TraceTree)
    rule_result: RuleResult | None = None


class RunbookReference(CamelModel):
    doc_id: str = ""
    title: str = ""
    section: str = ""
    score: float = 0.0


class DiagnosisResponse(CamelModel):
    experiment_id: str = ""
    fault_type: str = "UNKNOWN"
    fault_name: str = ""
    confidence: float = 0.2
    summary: str = ""
    phenomenon: list[str] = Field(default_factory=list)
    evidence: list[str] = Field(default_factory=list)
    root_causes: list[str] = Field(default_factory=list)
    suggestions: list[str] = Field(default_factory=list)
    runbook_references: list[RunbookReference] = Field(default_factory=list)
    fallback: bool = False
