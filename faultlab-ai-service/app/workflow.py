import logging
from typing import Any

from app.config import settings
from app.fallback import build_fallback_report
from app.json_parser import parse_and_validate_json
from app.llm_client import LlmClient
from app.prompt_builder import PromptBuilder
from app.schemas import DiagnosisRequest, DiagnosisResponse, TraceNode

logger = logging.getLogger(__name__)


def run_diagnosis_workflow(
    request: DiagnosisRequest,
    llm_client: LlmClient | None = None,
    prompt_builder: PromptBuilder | None = None,
) -> DiagnosisResponse:
    try:
        trace_summary = build_trace_summary(request)
        if not should_call_llm():
            return fallback_if_needed(request, build_fallback_report(request))

        system_prompt, user_prompt = build_prompt(request, trace_summary, prompt_builder)
        response = call_llm_and_parse(request, system_prompt, user_prompt, llm_client)
        return fallback_if_needed(request, response)
    except Exception as exc:
        logger.exception("Diagnosis workflow failed: %s", exc)
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
    prompt_builder: PromptBuilder | None = None,
) -> tuple[str, str]:
    builder = prompt_builder or PromptBuilder()
    return builder.build(request, trace_summary)


def call_llm_and_parse(
    request: DiagnosisRequest,
    system_prompt: str,
    user_prompt: str,
    llm_client: LlmClient | None = None,
) -> DiagnosisResponse:
    client = llm_client or LlmClient()
    last_error: Exception | None = None
    max_attempts = max(1, settings.llm_max_retries + 1)

    for attempt in range(1, max_attempts + 1):
        try:
            raw_content = call_llm(client, system_prompt, user_prompt)
            return parse_and_validate_json(raw_content, request)
        except Exception as exc:
            last_error = exc
            logger.warning("LLM diagnosis attempt %s failed: %s", attempt, exc)

    raise RuntimeError("LLM diagnosis failed after retries") from last_error


def call_llm(client: LlmClient, system_prompt: str, user_prompt: str) -> str:
    return client.generate(system_prompt, user_prompt)


def fallback_if_needed(
    request: DiagnosisRequest,
    response: DiagnosisResponse,
) -> DiagnosisResponse:
    if not response.summary:
        return build_fallback_report(request)
    return response
