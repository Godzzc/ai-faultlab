from datetime import UTC, datetime
from enum import StrEnum
from typing import Any
from uuid import uuid4

from pydantic import Field

from app.agent.state import DiagnosisAgentState
from app.retrieval.models import RunbookChunk
from app.schemas import CamelModel, DiagnosisRequest, DiagnosisResponse


class AgentStageStatus(StrEnum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    SKIPPED = "SKIPPED"


class AgentStageRecord(CamelModel):
    state: DiagnosisAgentState
    started_at: datetime | None = None
    finished_at: datetime | None = None
    duration_ms: int | None = None
    status: AgentStageStatus = AgentStageStatus.PENDING
    error_code: str | None = None
    error_message: str | None = None
    warnings: list[str] = Field(default_factory=list)


class AgentRunSummary(CamelModel):
    request_id: str
    experiment_id: str | None = None
    current_state: DiagnosisAgentState
    created_at: datetime
    completed_at: datetime | None = None
    total_duration_ms: int | None = None
    stage_records: list[AgentStageRecord] = Field(default_factory=list)
    warnings: list[str] = Field(default_factory=list)
    fallback_reason: str | None = None
    error_code: str | None = None
    error_message: str | None = None


class DiagnosisAgentContext(CamelModel):
    request_id: str = Field(default_factory=lambda: str(uuid4()))
    experiment_id: str | None = None
    current_state: DiagnosisAgentState = DiagnosisAgentState.RECEIVED
    evidence_package: DiagnosisRequest
    analysis_result: dict[str, Any] | None = None
    trace_summary: dict[str, Any] | None = None
    retrieved_chunks: list[RunbookChunk] = Field(default_factory=list)
    validated_references: list[dict[str, Any]] = Field(default_factory=list)
    report: DiagnosisResponse | None = None
    warnings: list[str] = Field(default_factory=list)
    fallback_reason: str | None = None
    error_code: str | None = None
    error_message: str | None = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(UTC))
    completed_at: datetime | None = None
    stage_records: list[AgentStageRecord] = Field(default_factory=list)

    @classmethod
    def from_request(cls, request: DiagnosisRequest) -> "DiagnosisAgentContext":
        request_id = request.request_id or str(uuid4())
        experiment_id = request.experiment.experiment_id
        if not experiment_id and request.rule_result:
            experiment_id = request.rule_result.experiment_id
        return cls(
            request_id=request_id,
            experiment_id=experiment_id or None,
            evidence_package=request,
        )

    def to_summary(self) -> AgentRunSummary:
        total_duration_ms = None
        if self.completed_at:
            total_duration_ms = int((self.completed_at - self.created_at).total_seconds() * 1000)
        return AgentRunSummary(
            request_id=self.request_id,
            experiment_id=self.experiment_id,
            current_state=self.current_state,
            created_at=self.created_at,
            completed_at=self.completed_at,
            total_duration_ms=total_duration_ms,
            stage_records=self.stage_records,
            warnings=self.warnings,
            fallback_reason=self.fallback_reason,
            error_code=self.error_code,
            error_message=self.error_message,
        )
