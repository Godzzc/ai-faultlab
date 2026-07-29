import json
import re
from typing import Any

from app.schemas import DiagnosisRequest, DiagnosisResponse


CODE_BLOCK_PATTERN = re.compile(r"^\s*```(?:json)?\s*(.*?)\s*```\s*$", re.DOTALL | re.IGNORECASE)


def parse_and_validate_json(raw_content: str, request: DiagnosisRequest) -> DiagnosisResponse:
    if not raw_content or not raw_content.strip():
        raise ValueError("LLM returned empty content")

    parsed = json.loads(strip_code_block(raw_content))
    if not isinstance(parsed, dict):
        raise ValueError("LLM output must be a JSON object")

    normalized = normalize_response_dict(parsed, request)
    response = DiagnosisResponse.model_validate(normalized)
    response.confidence = clamp_confidence(response.confidence)
    response.fallback = False
    return response


def strip_code_block(raw_content: str) -> str:
    match = CODE_BLOCK_PATTERN.match(raw_content)
    if match:
        return match.group(1).strip()
    return raw_content.strip()


def normalize_response_dict(data: dict[str, Any], request: DiagnosisRequest) -> dict[str, Any]:
    rule_result = request.rule_result
    experiment = request.experiment
    fault_type = (
        data.get("faultType")
        or (rule_result.fault_type if rule_result else "")
        or experiment.scenario_code
        or "UNKNOWN"
    )

    return {
        "experimentId": data.get("experimentId") or experiment.experiment_id,
        "faultType": fault_type,
        "faultName": data.get("faultName") or (rule_result.fault_name if rule_result else "") or "",
        "confidence": clamp_confidence(data.get("confidence", rule_result.confidence if rule_result else 0.2)),
        "summary": data.get("summary") or "当前证据不足，暂未生成明确故障结论。",
        "phenomenon": ensure_list(data.get("phenomenon")),
        "evidence": ensure_list(data.get("evidence")),
        "rootCauses": ensure_list(data.get("rootCauses")),
        "suggestions": ensure_list(data.get("suggestions")),
        "runbookReferences": ensure_list(data.get("runbookReferences")),
        "fallback": False,
    }


def ensure_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def clamp_confidence(value: Any) -> float:
    try:
        confidence = float(value)
    except (TypeError, ValueError):
        confidence = 0.2
    return min(1.0, max(0.0, confidence))
