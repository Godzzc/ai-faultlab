from app.schemas import DiagnosisRequest, DiagnosisResponse


def build_fallback_report(
    request: DiagnosisRequest,
    summary: str = "AI 诊断服务当前使用降级报告，暂未生成明确故障结论。",
) -> DiagnosisResponse:
    rule_result = request.rule_result
    experiment = request.experiment

    if rule_result and rule_result.matched:
        return DiagnosisResponse(
            experiment_id=rule_result.experiment_id or experiment.experiment_id,
            fault_type=rule_result.fault_type or experiment.scenario_code or "UNKNOWN",
            fault_name=rule_result.fault_name,
            confidence=rule_result.confidence,
            summary=summary,
            evidence=rule_result.evidence,
            suggestions=rule_result.suggestions,
            fallback=True,
        )

    return DiagnosisResponse(
        experiment_id=experiment.experiment_id,
        fault_type=experiment.scenario_code or "UNKNOWN",
        confidence=0.2,
        summary=summary,
        fallback=True,
    )
