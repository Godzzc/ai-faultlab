from typing import Any

from app.retrieval.tokenizer import tokenize_any
from app.schemas import DiagnosisRequest

MAX_QUERY_TEXT_LENGTH = 3000
MAX_LIST_ITEMS = 20


def request_fault_type(request: DiagnosisRequest) -> str:
    if request.rule_result and request.rule_result.fault_type:
        return request.rule_result.fault_type
    return request.experiment.scenario_code or ""


def build_retrieval_query(
    request: DiagnosisRequest,
    trace_summary: dict[str, Any],
) -> str:
    parts: list[str] = []
    fault_type = request_fault_type(request)
    scenario_code = _clean_text(request.experiment.scenario_code)
    if fault_type:
        parts.append(f"faultType={fault_type}")
    if scenario_code:
        parts.append(f"scenarioCode={scenario_code}")

    if request.rule_result:
        rule_result = request.rule_result
        parts.extend([
            _field("faultName", rule_result.fault_name),
            _field("reason", rule_result.reason),
            _list_field("evidence", rule_result.evidence),
            _list_field("suggestions", rule_result.suggestions),
        ])

    parts.extend(_metric_parts(request))
    parts.extend(_domain_hint_parts(request))
    parts.extend(_trace_parts(trace_summary or {}))
    return _bounded_query_text(_dedupe_parts(parts))


def _metric_parts(request: DiagnosisRequest) -> list[str]:
    parts: list[str] = []
    for metric in request.metrics or []:
        metric_name = _clean_text(metric.metric_name)
        metric_value = _clean_text(metric.metric_value)
        metric_unit = _clean_text(metric.metric_unit)
        component = _clean_text(metric.component)
        metric_fragments = [
            _field("metricName", metric_name),
            _field("metricValue", metric_value),
            _field("metricUnit", metric_unit),
            _field("component", component),
        ]
        metric_text = " ".join(item for item in metric_fragments if item)
        if metric_name and metric_value:
            metric_text = f"metric {metric_name}={metric_value}{metric_unit} {metric_text}".strip()
        if metric_text:
            parts.append(metric_text)
    return parts


def _trace_parts(trace_summary: dict[str, Any]) -> list[str]:
    parts = [
        _field("trace nodeCount", trace_summary.get("nodeCount")),
        _field("traceNodeCount", trace_summary.get("nodeCount")),
        _field("traceSpanCount", trace_summary.get("spanCount")),
    ]
    for prefix, spans in [
        ("slowSpan", trace_summary.get("slowSpans")),
        ("errorSpan", trace_summary.get("errorSpans")),
    ]:
        for span in _limited_list(spans):
            if not isinstance(span, dict):
                parts.append(_field(prefix, span))
                continue
            span_parts = [
                _field("operation", span.get("operationName") or span.get("operation")),
                _field("component", span.get("component")),
                _field("durationMs", span.get("durationMs")),
                _field("status", span.get("status")),
            ]
            span_text = " ".join(item for item in span_parts if item)
            if span_text:
                parts.append(f"{prefix} {span_text}")
    return parts


def _domain_hint_parts(request: DiagnosisRequest) -> list[str]:
    fault_type = request_fault_type(request)
    tokens = _request_tokens(request)
    hints: list[str] = []

    if fault_type == "MQ_BACKLOG":
        if tokens & {"avgconsumems", "consumerdelayms", "slowsqlcount"}:
            hints.append("domainHint=slow consumer consume latency")
        if tokens & {"consumercount", "consumerthreadcount", "publishrate", "ackrate"}:
            hints.append("domainHint=consumer concurrency")
        if tokens & {"dblatencyms", "redislatencyms"}:
            hints.append("domainHint=downstream slow")
        if tokens & {"retrycount", "redelivercount", "consumeerrorcount"}:
            hints.append("domainHint=retry redelivery")
        if tokens & {"deadlettercount", "dlq"}:
            hints.append("domainHint=dead letter DLQ")
        if tokens & {"queueunackedcount", "ackrate"}:
            hints.append("domainHint=unacked messages ACK")

    if fault_type == "THREAD_POOL_SATURATION":
        if tokens & {"avgtaskdurationms", "tasksleepms"}:
            hints.append("domainHint=slow task")
        if tokens & {"queuesize", "queuecapacity", "avgqueuewaitms"}:
            hints.append("domainHint=queue backlog queue capacity")
        if tokens & {"rejectedtaskcount", "rejectedexecutionhandler"}:
            hints.append("domainHint=rejection policy")
        if tokens & {"blockingiocount", "remotecalltimeoutcount"}:
            hints.append("domainHint=blocking dependency")
        if tokens & {"corepoolsize", "maximumpoolsize", "poolsize"}:
            hints.append("domainHint=thread pool parameter tuning")
        if tokens & {"sharedexecutor", "criticaltaskdelayms", "slowbusinesstaskcount"}:
            hints.append("domainHint=thread pool isolation")

    if fault_type == "IDEMPOTENCY_CONFLICT":
        if tokens & {"hashmismatchcount", "requesthashchecked"}:
            hints.append("domainHint=requestHash hash mismatch")
        if tokens & {"redissetnxfailcount", "redissetnxsuccesscount"}:
            hints.append("domainHint=Redis SETNX duplicate request")
        if tokens & {"dbuniqueindexexists", "duplicatebusinessrowcount"}:
            hints.append("domainHint=unique index database constraint")
        if tokens & {"processingagems"} or "processing" in tokens:
            hints.append("domainHint=PROCESSING state")
        if tokens & {"historyresultreuse"} or "success" in tokens:
            hints.append("domainHint=SUCCESS result cache")
        if tokens & {"rediserrorcount", "redistimeoutms"}:
            hints.append("domainHint=Redis error fail-close")
        if tokens & {"missingkeycount", "unstablekeycount"}:
            hints.append("domainHint=stable Idempotency-Key")
        if tokens & {"duplicatecount", "clientretrycount"}:
            hints.append("domainHint=duplicate submit retry request")
    return hints


def _request_tokens(request: DiagnosisRequest) -> set[str]:
    values: list[Any] = [request.experiment.scenario_code]
    if request.rule_result:
        values.extend([
            request.rule_result.fault_type,
            request.rule_result.fault_name,
            request.rule_result.reason,
            request.rule_result.evidence,
            request.rule_result.suggestions,
        ])
    values.extend(metric.model_dump(by_alias=True) for metric in request.metrics or [])
    return {
        token.lower()
        for value in values
        for token in _tokens_from_value(value)
    }


def _tokens_from_value(value: Any) -> list[str]:
    return tokenize_any(value)


def _field(name: str, value: Any) -> str:
    cleaned = _clean_text(value)
    return f"{name}={cleaned}" if cleaned else ""


def _list_field(name: str, values: Any) -> str:
    items = [_clean_text(item) for item in _limited_list(values)]
    items = [item for item in items if item]
    return f"{name}={' | '.join(_dedupe_parts(items))}" if items else ""


def _limited_list(values: Any) -> list[Any]:
    if values is None:
        return []
    if isinstance(values, list):
        return values[:MAX_LIST_ITEMS]
    if isinstance(values, tuple):
        return list(values[:MAX_LIST_ITEMS])
    return [values]


def _clean_text(value: Any) -> str:
    if value is None:
        return ""
    text = str(value).strip()
    if not text or text.lower() == "none":
        return ""
    return " ".join(text.split())


def _dedupe_parts(parts: list[str]) -> list[str]:
    seen: set[str] = set()
    deduped: list[str] = []
    for part in parts:
        cleaned = _clean_text(part)
        if not cleaned:
            continue
        key = cleaned.lower()
        if key in seen:
            continue
        seen.add(key)
        deduped.append(cleaned)
    return deduped


def _bounded_query_text(parts: list[str]) -> str:
    text = "\n".join(parts)
    if len(text) <= MAX_QUERY_TEXT_LENGTH:
        return text
    return text[:MAX_QUERY_TEXT_LENGTH].rsplit("\n", 1)[0] or text[:MAX_QUERY_TEXT_LENGTH]
