import logging
from typing import Any

from app.fallback import build_fallback_report
from app.schemas import DiagnosisRequest, DiagnosisResponse

logger = logging.getLogger(__name__)


FAULT_SUMMARIES = {
    "MQ_BACKLOG": "本次实验检测到 MQ 消息堆积风险。",
    "THREAD_POOL_SATURATION": "本次实验检测到线程池饱和风险。",
    "IDEMPOTENCY_CONFLICT": "本次实验检测到幂等性冲突风险。",
}


def run_diagnosis_workflow(request: DiagnosisRequest) -> DiagnosisResponse:
    try:
        trace_summary = build_trace_summary(request)
        response = build_template_report(request, trace_summary)
        validated_response = validate_response(response)
        return fallback_if_needed(request, validated_response)
    except Exception as exc:
        logger.exception("Diagnosis workflow failed: %s", exc)
        return build_fallback_report(request)


def build_trace_summary(request: DiagnosisRequest) -> dict[str, Any]:
    return {
        "traceId": request.trace_tree.trace_id or request.experiment.trace_id,
        "rootSpanCount": len(request.trace_tree.roots),
    }


def build_template_report(
    request: DiagnosisRequest,
    trace_summary: dict[str, Any],
) -> DiagnosisResponse:
    del trace_summary

    rule_result = request.rule_result
    experiment = request.experiment

    if rule_result and rule_result.matched:
        fault_type = rule_result.fault_type or experiment.scenario_code or "UNKNOWN"
        return DiagnosisResponse(
            experiment_id=rule_result.experiment_id or experiment.experiment_id,
            fault_type=fault_type,
            fault_name=rule_result.fault_name,
            confidence=rule_result.confidence,
            summary=FAULT_SUMMARIES.get(fault_type, f"本次实验检测到 {fault_type} 风险。"),
            phenomenon=[rule_result.reason] if rule_result.reason else [],
            evidence=rule_result.evidence,
            suggestions=rule_result.suggestions,
            runbook_references=[],
            fallback=False,
        )

    return DiagnosisResponse(
        experiment_id=experiment.experiment_id,
        fault_type=experiment.scenario_code or "UNKNOWN",
        confidence=0.2,
        summary="当前证据不足，暂未生成明确故障结论。",
        runbook_references=[],
        fallback=True,
    )


def validate_response(response: DiagnosisResponse) -> DiagnosisResponse:
    return DiagnosisResponse.model_validate(response)


def fallback_if_needed(
    request: DiagnosisRequest,
    response: DiagnosisResponse,
) -> DiagnosisResponse:
    if not response.summary:
        return build_fallback_report(request)
    return response
