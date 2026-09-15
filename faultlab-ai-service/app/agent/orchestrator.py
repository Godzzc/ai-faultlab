import logging
from datetime import UTC, datetime
from typing import Any

from app.agent.models import AgentToolCallRecord, AgentToolCallStatus, DiagnosisAgentContext
from app.agent.run_store import AGENT_RUN_STORE, AgentRunStore
from app.agent.state import DiagnosisAgentState
from app.agent.state_machine import AgentStageError, DiagnosisAgentStateMachine
from app.agent.tool_registry import AgentToolRegistry, build_default_tool_registry
from app.agent.tools.models import AgentToolResult
from app.fallback import build_fallback_report
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.schemas import DiagnosisRequest, DiagnosisResponse

logger = logging.getLogger(__name__)


class AgentErrorCode:
    AGENT_STAGE_FAILED = "AGENT_STAGE_FAILED"
    TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED"
    EVIDENCE_COLLECTION_FAILED = "EVIDENCE_COLLECTION_FAILED"
    RETRIEVAL_FAILED = "RETRIEVAL_FAILED"
    REFERENCE_VALIDATION_FAILED = "REFERENCE_VALIDATION_FAILED"
    MODEL_PROVIDER_FAILED = "MODEL_PROVIDER_FAILED"


class ReferenceValidationError(Exception):
    pass


class AgentToolExecutionError(Exception):
    def __init__(self, tool_name: str, error_code: str, message: str) -> None:
        super().__init__(message)
        self.tool_name = tool_name
        self.error_code = error_code
        self.message = message


class DiagnosisAgentOrchestrator:
    def __init__(
        self,
        run_store: AgentRunStore | None = None,
        runbook_chunk_loader: KeywordRunbookRetriever | None = None,
    ) -> None:
        self.run_store = run_store or AGENT_RUN_STORE
        self.runbook_chunk_loader = runbook_chunk_loader or KeywordRunbookRetriever()

    def run(
        self,
        request: DiagnosisRequest,
        llm_client: Any | None = None,
        prompt_builder: Any | None = None,
        model_router: Any | None = None,
        retrieval_service: Any | None = None,
    ) -> DiagnosisResponse:
        context = DiagnosisAgentContext.from_request(request)
        state_machine = DiagnosisAgentStateMachine(context)
        tool_registry = self._build_tool_registry(
            llm_client=llm_client,
            prompt_builder=prompt_builder,
            model_router=model_router,
            retrieval_service=retrieval_service,
        )

        try:
            state_machine.run_stage(
                DiagnosisAgentState.COLLECT_EVIDENCE,
                lambda: self._collect_evidence(context, tool_registry),
                error_code=AgentErrorCode.EVIDENCE_COLLECTION_FAILED,
            )
            state_machine.run_stage(
                DiagnosisAgentState.ANALYZE,
                lambda: self._analyze(context),
            )
            state_machine.run_stage(
                DiagnosisAgentState.RETRIEVE,
                lambda: self._retrieve(context, tool_registry),
                error_code=AgentErrorCode.RETRIEVAL_FAILED,
            )
            if context.fallback_reason:
                state_machine.run_stage(
                    DiagnosisAgentState.FALLBACK,
                    lambda: self._fallback(context),
                )
            else:
                state_machine.run_stage(
                    DiagnosisAgentState.VALIDATE,
                    lambda: self._validate(context, tool_registry),
                    error_code=AgentErrorCode.REFERENCE_VALIDATION_FAILED,
                )
            state_machine.run_stage(
                DiagnosisAgentState.GENERATE_REPORT,
                lambda: self._generate_report(context, tool_registry),
                error_code=AgentErrorCode.MODEL_PROVIDER_FAILED,
            )
            state_machine.complete()
            return context.report or build_fallback_report(request)
        except AgentStageError as exc:
            logger.exception(
                "Diagnosis agent failed requestId=%s state=%s errorCode=%s error=%s",
                context.request_id,
                exc.state,
                exc.error_code,
                exc.message,
            )
            return build_fallback_report(request)
        except Exception as exc:
            logger.exception("Diagnosis agent failed requestId=%s error=%s", context.request_id, exc)
            if context.current_state != DiagnosisAgentState.FAILED:
                state_machine.fail(AgentErrorCode.AGENT_STAGE_FAILED, str(exc))
            return build_fallback_report(request)
        finally:
            self.run_store.save(context)

    def _build_tool_registry(
        self,
        llm_client: Any | None,
        prompt_builder: Any | None,
        model_router: Any | None,
        retrieval_service: Any | None,
    ) -> AgentToolRegistry:
        return build_default_tool_registry(
            retrieval_service=retrieval_service,
            runbook_chunk_loader=self.runbook_chunk_loader,
            llm_client=llm_client,
            prompt_builder=prompt_builder,
            model_router=model_router,
        )

    def _collect_evidence(self, context: DiagnosisAgentContext, tool_registry: AgentToolRegistry) -> None:
        result = self.execute_tool("evidence_collection", context, DiagnosisAgentState.COLLECT_EVIDENCE, tool_registry)
        data = result.data
        context.experiment_id = data.get("experimentId") or context.experiment_id
        if result.warnings:
            context.warnings.extend(result.warnings)

    def _analyze(self, context: DiagnosisAgentContext) -> None:
        from app import workflow

        request = context.evidence_package
        trace_summary = workflow.build_trace_summary(request)
        context.trace_summary = trace_summary
        context.analysis_result = {
            "faultType": (request.rule_result.fault_type if request.rule_result else "")
            or request.experiment.scenario_code
            or "UNKNOWN",
            "metricCount": len(request.metrics),
            "traceSummary": trace_summary,
            "ruleMatched": bool(request.rule_result and request.rule_result.matched),
        }

    def _retrieve(
        self,
        context: DiagnosisAgentContext,
        tool_registry: AgentToolRegistry,
    ) -> None:
        result = self.execute_tool(
            "runbook_retrieval",
            context,
            DiagnosisAgentState.RETRIEVE,
            tool_registry,
            fail_fast=False,
        )
        if not result.success:
            context.retrieved_chunks = []
            context.fallback_reason = "runbook retrieval unavailable"
            context.warnings.append(f"Runbook retrieval failed: {result.error_message}")
            logger.warning(
                "Diagnosis agent retrieval fallback requestId=%s error=%s",
                context.request_id,
                result.error_message,
            )
            return
        context.retrieved_chunks = result.data.get("retrievedChunks", [])
        if result.warnings:
            context.warnings.extend(result.warnings)

    def _fallback(self, context: DiagnosisAgentContext) -> None:
        if not context.fallback_reason:
            context.fallback_reason = "agent fallback requested"
        context.warnings.append(context.fallback_reason)

    def _validate(self, context: DiagnosisAgentContext, tool_registry: AgentToolRegistry) -> None:
        result = self.execute_tool("reference_validation", context, DiagnosisAgentState.VALIDATE, tool_registry)
        context.validated_references = result.data.get("validatedReferences", [])

    def _generate_report(self, context: DiagnosisAgentContext, tool_registry: AgentToolRegistry) -> None:
        result = self.execute_tool(
            "diagnosis_report_generator",
            context,
            DiagnosisAgentState.GENERATE_REPORT,
            tool_registry,
        )
        context.report = result.data.get("report")

    def execute_tool(
        self,
        tool_name: str,
        context: DiagnosisAgentContext,
        state: DiagnosisAgentState,
        tool_registry: AgentToolRegistry,
        fail_fast: bool = True,
    ) -> AgentToolResult:
        tool = tool_registry.get(tool_name)
        record = AgentToolCallRecord(
            tool_name=tool.name,
            state=state,
            started_at=datetime.now(UTC),
            status=AgentToolCallStatus.RUNNING,
            input_summary=self._build_tool_input_summary(tool.name, context),
        )
        context.tool_calls.append(record)

        try:
            result = tool.execute(context)
        except Exception as exc:
            result = AgentToolResult.fail(
                tool_name=tool.name,
                error_code=AgentErrorCode.TOOL_EXECUTION_FAILED,
                error_message=str(exc),
            )

        record.finished_at = datetime.now(UTC)
        if record.started_at and record.finished_at:
            record.duration_ms = int((record.finished_at - record.started_at).total_seconds() * 1000)
        record.warnings = result.warnings
        record.output_summary = self._build_tool_output_summary(result)

        if result.success:
            record.status = AgentToolCallStatus.SUCCESS
            return result

        record.status = AgentToolCallStatus.FAILED
        record.error_code = result.error_code
        record.error_message = result.error_message
        if fail_fast:
            raise AgentToolExecutionError(
                tool.name,
                result.error_code or AgentErrorCode.TOOL_EXECUTION_FAILED,
                result.error_message or "Agent tool execution failed",
            )
        return result

    def _build_tool_input_summary(
        self,
        tool_name: str,
        context: DiagnosisAgentContext,
    ) -> dict[str, Any]:
        request = context.evidence_package
        rule_result = request.rule_result
        return {
            "toolName": tool_name,
            "requestId": context.request_id,
            "experimentId": context.experiment_id,
            "faultType": (rule_result.fault_type if rule_result else "") or request.experiment.scenario_code or "",
            "metricCount": len(request.metrics),
            "retrievedChunkCount": len(context.retrieved_chunks),
        }

    def _build_tool_output_summary(self, result: AgentToolResult) -> dict[str, Any]:
        summary: dict[str, Any] = {
            "success": result.success,
        }
        for key in ("experimentId", "faultType", "metricCount", "resultCount", "validatedCount", "invalidCount"):
            if key in result.data:
                summary[key] = result.data[key]
        if "report" in result.data:
            report = result.data["report"]
            summary["fallback"] = getattr(report, "fallback", None)
            summary["faultType"] = getattr(report, "fault_type", None)
        for key in ("fallback", "fallbackReason", "retrieverType", "model", "provider"):
            if key in result.metadata:
                summary[key] = result.metadata[key]
        return summary
