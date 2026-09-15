import logging
from typing import Any

from app.agent.models import DiagnosisAgentContext
from app.agent.run_store import AGENT_RUN_STORE, AgentRunStore
from app.agent.state import DiagnosisAgentState
from app.agent.state_machine import AgentStageError, DiagnosisAgentStateMachine
from app.fallback import build_fallback_report
from app.retrieval.keyword_runbook_retriever import KeywordRunbookRetriever
from app.retrieval.models import RunbookChunk
from app.schemas import DiagnosisRequest, DiagnosisResponse

logger = logging.getLogger(__name__)


class AgentErrorCode:
    AGENT_STAGE_FAILED = "AGENT_STAGE_FAILED"
    RETRIEVAL_FAILED = "RETRIEVAL_FAILED"
    REFERENCE_VALIDATION_FAILED = "REFERENCE_VALIDATION_FAILED"
    MODEL_PROVIDER_FAILED = "MODEL_PROVIDER_FAILED"


class ReferenceValidationError(Exception):
    pass


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

        try:
            state_machine.run_stage(
                DiagnosisAgentState.COLLECT_EVIDENCE,
                lambda: self._collect_evidence(context),
            )
            state_machine.run_stage(
                DiagnosisAgentState.ANALYZE,
                lambda: self._analyze(context),
            )
            state_machine.run_stage(
                DiagnosisAgentState.RETRIEVE,
                lambda: self._retrieve(context, retrieval_service),
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
                    lambda: self._validate(context),
                    error_code=AgentErrorCode.REFERENCE_VALIDATION_FAILED,
                )
            state_machine.run_stage(
                DiagnosisAgentState.GENERATE_REPORT,
                lambda: self._generate_report(
                    context,
                    llm_client=llm_client,
                    prompt_builder=prompt_builder,
                    model_router=model_router,
                ),
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

    def _collect_evidence(self, context: DiagnosisAgentContext) -> None:
        request = context.evidence_package
        if not context.experiment_id:
            context.experiment_id = request.experiment.experiment_id or None
        if not request.rule_result:
            context.warnings.append("ruleResult is missing; report may rely on experiment and metric evidence only")

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
        retrieval_service: Any | None,
    ) -> None:
        from app import workflow

        try:
            context.retrieved_chunks = workflow.retrieve_runbook(
                context.evidence_package,
                context.trace_summary or {},
                retrieval_service,
            )
        except Exception as exc:
            context.retrieved_chunks = []
            context.fallback_reason = "runbook retrieval unavailable"
            context.warnings.append(f"Runbook retrieval failed: {exc}")
            logger.warning(
                "Diagnosis agent retrieval fallback requestId=%s error=%s",
                context.request_id,
                exc,
            )

    def _fallback(self, context: DiagnosisAgentContext) -> None:
        if not context.fallback_reason:
            context.fallback_reason = "agent fallback requested"
        context.warnings.append(context.fallback_reason)

    def _validate(self, context: DiagnosisAgentContext) -> None:
        allowed_references = self._load_allowed_runbook_references()
        invalid = [
            f"{chunk.docId}#{chunk.section}"
            for chunk in context.retrieved_chunks
            if self._should_validate_chunk_against_runbooks(chunk)
            and (chunk.docId, chunk.section) not in allowed_references
        ]
        if invalid:
            raise ReferenceValidationError(
                "Retrieved Runbook references do not exist: " + ", ".join(invalid)
            )

        context.validated_references = [
            {
                "docId": chunk.docId,
                "title": chunk.title,
                "section": chunk.section,
                "score": chunk.score,
            }
            for chunk in context.retrieved_chunks
        ]

    def _generate_report(
        self,
        context: DiagnosisAgentContext,
        llm_client: Any | None,
        prompt_builder: Any | None,
        model_router: Any | None,
    ) -> None:
        from app import workflow

        request = context.evidence_package
        if context.fallback_reason == "runbook retrieval unavailable":
            context.report = build_fallback_report(request)
            return

        if not workflow.should_call_llm():
            logger.info("fallback reason=llm_disabled_or_api_key_missing")
            context.report = workflow.fallback_if_needed(request, build_fallback_report(request))
            return

        system_prompt, user_prompt = workflow.build_prompt(
            request,
            context.trace_summary or {},
            context.retrieved_chunks,
            prompt_builder,
        )
        route_result = workflow.select_model(request, context.trace_summary or {}, model_router)
        response = workflow.call_llm_and_parse(
            request,
            system_prompt,
            user_prompt,
            route_result.model,
            llm_client,
        )
        workflow.validate_runbook_references(response, context.retrieved_chunks)
        context.report = workflow.fallback_if_needed(request, response)

    def _load_allowed_runbook_references(self) -> set[tuple[str, str]]:
        chunks: list[RunbookChunk] = self.runbook_chunk_loader._load_chunks()
        return {(chunk.docId, chunk.section) for chunk in chunks}

    def _should_validate_chunk_against_runbooks(self, chunk: RunbookChunk) -> bool:
        return bool(chunk.metadata.get("path"))
