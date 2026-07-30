import logging
from typing import Any

from app.config import settings
from app.fallback import build_fallback_report
from app.json_parser import parse_and_validate_json
from app.llm_client import LlmClient
from app.model_router import ModelRouter
from app.prompt_builder import PromptBuilder
from app.retrieval.models import RunbookChunk
from app.retrieval.retrieval_service import RetrievalService
from app.schemas import DiagnosisRequest, DiagnosisResponse, RunbookReference, TraceNode

logger = logging.getLogger(__name__)


def run_diagnosis_workflow(
    request: DiagnosisRequest,
    llm_client: LlmClient | None = None,
    prompt_builder: PromptBuilder | None = None,
    model_router: ModelRouter | None = None,
    retrieval_service: RetrievalService | None = None,
) -> DiagnosisResponse:
    try:
        trace_summary = build_trace_summary(request)
        runbook_chunks = retrieve_runbook(request, trace_summary, retrieval_service)
        if not should_call_llm():
            logger.info("fallback reason=llm_disabled_or_api_key_missing")
            return fallback_if_needed(request, build_fallback_report(request))

        system_prompt, user_prompt = build_prompt(request, trace_summary, runbook_chunks, prompt_builder)
        route_result = select_model(request, trace_summary, model_router)
        response = call_llm_and_parse(
            request,
            system_prompt,
            user_prompt,
            route_result.model,
            llm_client,
        )
        validate_runbook_references(response, runbook_chunks)
        return fallback_if_needed(request, response)
    except Exception as exc:
        logger.exception("Diagnosis workflow failed: %s", exc)
        logger.info("fallback reason=workflow_exception")
        return build_fallback_report(request)


def should_call_llm() -> bool:
    if not settings.llm_enabled:
        logger.info("LLM is disabled, using fallback report")
        return False
    if not settings.dashscope_api_key:
        logger.info("DASHSCOPE_API_KEY is empty, using fallback report")
        return False
    return True


def build_trace_summary(request: DiagnosisRequest) -> dict[str, Any]:
    roots = request.trace_tree.roots or []
    flattened_spans = flatten_trace_nodes(roots)
    slow_spans = [
        {
            "operationName": span.operation_name,
            "component": span.component,
            "durationMs": span.duration_ms,
        }
        for span in flattened_spans
        if span.duration_ms and span.duration_ms >= 1000
    ]
    error_spans = [
        {
            "operationName": span.operation_name,
            "component": span.component,
            "status": span.status,
        }
        for span in flattened_spans
        if str(span.status).upper() == "ERROR"
    ]
    return {
        "traceId": request.trace_tree.trace_id or request.experiment.trace_id,
        "rootSpanCount": len(roots),
        "spanCount": len(flattened_spans),
        "nodeCount": len(flattened_spans),
        "slowSpans": slow_spans,
        "errorSpans": error_spans,
    }


def flatten_trace_nodes(nodes: list[TraceNode]) -> list[TraceNode]:
    flattened: list[TraceNode] = []
    for node in nodes:
        flattened.append(node)
        flattened.extend(flatten_trace_nodes(node.children or []))
    return flattened


def build_prompt(
    request: DiagnosisRequest,
    trace_summary: dict[str, Any],
    runbook_chunks: list[RunbookChunk] | None = None,
    prompt_builder: PromptBuilder | None = None,
) -> tuple[str, str]:
    builder = prompt_builder or PromptBuilder()
    return builder.build(request, trace_summary, runbook_chunks or [])


def retrieve_runbook(
    request: DiagnosisRequest,
    trace_summary: dict[str, Any],
    retrieval_service: RetrievalService | None = None,
) -> list[RunbookChunk]:
    service = retrieval_service or RetrievalService()
    return service.retrieve_runbooks(request, trace_summary)


def select_model(
    request: DiagnosisRequest,
    trace_summary: dict[str, Any],
    model_router: ModelRouter | None = None,
) -> Any:
    router = model_router or ModelRouter()
    return router.select_model(request, trace_summary)


def call_llm_and_parse(
    request: DiagnosisRequest,
    system_prompt: str,
    user_prompt: str,
    model: str,
    llm_client: LlmClient | None = None,
) -> DiagnosisResponse:
    client = llm_client or LlmClient()
    last_error: Exception | None = None
    max_attempts = max(1, settings.llm_max_retries + 1)

    for attempt in range(1, max_attempts + 1):
        try:
            raw_content = call_llm(client, system_prompt, user_prompt, model)
            return parse_and_validate_json(raw_content, request)
        except Exception as exc:
            last_error = exc
            logger.warning("LLM diagnosis attempt %s failed: %s", attempt, exc)

    logger.info("fallback reason=llm_call_or_parse_failed")
    raise RuntimeError("LLM diagnosis failed after retries") from last_error


def call_llm(client: LlmClient, system_prompt: str, user_prompt: str, model: str) -> str:
    return client.generate(system_prompt, user_prompt, model=model)


def fallback_if_needed(
    request: DiagnosisRequest,
    response: DiagnosisResponse,
) -> DiagnosisResponse:
    if not response.summary:
        return build_fallback_report(request)
    return response


def validate_runbook_references(
    response: DiagnosisResponse,
    runbook_chunks: list[RunbookChunk],
) -> DiagnosisResponse:
    allowed = {
        (chunk.docId, chunk.section): chunk
        for chunk in runbook_chunks
    }
    if not allowed:
        response.runbook_references = []
        logger.info("No valid runbookReferences retained")
        return response

    retained: list[RunbookReference] = []
    seen: set[tuple[str, str]] = set()
    for reference in response.runbook_references or []:
        key = (reference.doc_id, reference.section)
        chunk = allowed.get(key)
        if not chunk or key in seen:
            continue
        retained.append(
            RunbookReference(
                doc_id=chunk.docId,
                title=chunk.title,
                section=chunk.section,
                score=chunk.score,
            )
        )
        seen.add(key)

    response.runbook_references = retained
    if not retained:
        logger.info("No valid runbookReferences retained")
    return response
