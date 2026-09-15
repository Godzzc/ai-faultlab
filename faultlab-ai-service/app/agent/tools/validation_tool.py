from app.agent.models import DiagnosisAgentContext
from app.agent.tools.base import AgentTool
from app.agent.tools.models import AgentToolResult
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk


class RunbookReferenceValidationTool(AgentTool):
    def __init__(self, runbook_chunk_loader: KeywordRunbookRetriever | None = None) -> None:
        self.runbook_chunk_loader = runbook_chunk_loader or KeywordRunbookRetriever()

    @property
    def name(self) -> str:
        return "reference_validation"

    @property
    def description(self) -> str:
        return "Validate retrieved Runbook references against local Runbook sections."

    def execute(self, context: DiagnosisAgentContext) -> AgentToolResult:
        allowed_references = self._load_allowed_runbook_references()
        invalid = [
            f"{chunk.docId}#{chunk.section}"
            for chunk in context.retrieved_chunks
            if self._should_validate_chunk_against_runbooks(chunk)
            and (chunk.docId, chunk.section) not in allowed_references
        ]
        if invalid:
            return AgentToolResult.fail(
                tool_name=self.name,
                error_code="REFERENCE_VALIDATION_FAILED",
                error_message="Retrieved Runbook references do not exist: " + ", ".join(invalid),
                data={"validatedReferences": [], "invalidCount": len(invalid)},
            )

        validated_references = [
            {
                "docId": chunk.docId,
                "title": chunk.title,
                "section": chunk.section,
                "score": chunk.score,
            }
            for chunk in context.retrieved_chunks
        ]
        return AgentToolResult.ok(
            tool_name=self.name,
            data={
                "validatedReferences": validated_references,
                "validatedCount": len(validated_references),
            },
        )

    def _load_allowed_runbook_references(self) -> set[tuple[str, str]]:
        chunks: list[RunbookChunk] = self.runbook_chunk_loader._load_chunks()
        return {(chunk.docId, chunk.section) for chunk in chunks}

    def _should_validate_chunk_against_runbooks(self, chunk: RunbookChunk) -> bool:
        return bool(chunk.metadata.get("path"))
