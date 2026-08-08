from typing import Any

from app.schemas import DiagnosisRequest


def request_fault_type(request: DiagnosisRequest) -> str:
    if request.rule_result and request.rule_result.fault_type:
        return request.rule_result.fault_type
    return request.experiment.scenario_code or ""


def build_retrieval_query(
    request: DiagnosisRequest,
    trace_summary: dict[str, Any],
) -> str:
    parts: list[str] = []
    if request.rule_result:
        parts.extend([
            request.rule_result.fault_type,
            request.rule_result.fault_name,
            request.rule_result.reason,
            " ".join(request.rule_result.evidence or []),
            " ".join(request.rule_result.suggestions or []),
        ])

    metric_parts = [
        f"{metric.metric_name}={metric.metric_value}{metric.metric_unit} component={metric.component}"
        for metric in request.metrics or []
    ]
    parts.append(" ".join(metric_parts))
    parts.append(f"scenarioCode={request.experiment.scenario_code}")
    parts.append(f"trace nodeCount={trace_summary.get('nodeCount', 0)}")
    parts.append(f"slowSpans={trace_summary.get('slowSpans', [])}")
    parts.append(f"errorSpans={trace_summary.get('errorSpans', [])}")
    return "\n".join(part for part in parts if part)
