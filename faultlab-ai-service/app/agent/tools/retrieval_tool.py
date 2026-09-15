from typing import Any

from app.agent.models import DiagnosisAgentContext
from app.agent.tools.base import AgentTool
from app.agent.tools.models import AgentToolResult


class RunbookRetrievalTool(AgentTool):
    def __init__(self, retrieval_service: Any | None = None) -> None:
        self.retrieval_service = retrieval_service

    @property
    def name(self) -> str:
        return "runbook_retrieval"

    @property
    def description(self) -> str:
        return "Retrieve Runbook chunks through the existing retrieval service."

    def execute(self, context: DiagnosisAgentContext) -> AgentToolResult:
        from app import workflow

        try:
            chunks = workflow.retrieve_runbook(
                context.evidence_package,
                context.trace_summary or {},
                self.retrieval_service,
            )
        except Exception as exc:
            return AgentToolResult.fail(
                tool_name=self.name,
                error_code="RETRIEVAL_FAILED",
                error_message=str(exc),
                data={"retrievedChunks": [], "resultCount": 0},
            )

        warnings: list[str] = []
        metadata: dict[str, Any] = {
            "fallback": False,
            "retrieverType": type(self.retrieval_service).__name__ if self.retrieval_service else "RetrievalService",
        }
        if not chunks:
            warnings.append("runbook retrieval returned no chunks")
            metadata["fallback"] = True

        return AgentToolResult.ok(
            tool_name=self.name,
            data={
                "retrievedChunks": chunks,
                "resultCount": len(chunks),
            },
            warnings=warnings,
            metadata=metadata,
        )
