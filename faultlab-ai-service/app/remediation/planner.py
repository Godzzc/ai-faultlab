from __future__ import annotations

from typing import TYPE_CHECKING

from app.remediation.models import (
    ExpectedMetricDirection,
    ExpectedMetricEffect,
    RemediationAction,
    RemediationPlan,
    RemediationPlanStatus,
)
from app.remediation.policy import validate_action

if TYPE_CHECKING:
    from app.agent.models import DiagnosisAgentContext


class RemediationPlanner:
    def plan(self, context: DiagnosisAgentContext) -> RemediationPlan:
        request = context.evidence_package
        scenario_code = self._scenario_code(context)
        evidence = self._collect_evidence(context)
        metrics = self._metric_values(context)

        if scenario_code == "CACHE_BREAKDOWN":
            plan = self._plan_cache_breakdown(context, evidence, metrics)
        elif scenario_code == "DB_CONNECTION_POOL_EXHAUSTION":
            plan = self._plan_db_connection_pool(context, evidence, metrics)
        elif scenario_code == "DOWNSTREAM_TIMEOUT":
            plan = self._plan_downstream_timeout(context, evidence, metrics)
        elif scenario_code == "RETRY_STORM":
            plan = self._plan_retry_storm(context, evidence, metrics)
        else:
            return RemediationPlan(
                experiment_id=request.experiment.experiment_id or context.experiment_id,
                scenario_code=scenario_code,
                status=RemediationPlanStatus.UNSUPPORTED,
                confidence=0.0,
                evidence=evidence,
                warnings=[f"remediation planning unsupported for scenario {scenario_code}"],
            )

        validation_errors = [
            error
            for action in plan.actions
            for error in validate_action(plan.scenario_code, action)
        ]
        if validation_errors:
            plan.status = RemediationPlanStatus.INVALID
            plan.warnings.extend(error.message for error in validation_errors)
        return plan

    def _plan_cache_breakdown(
        self,
        context: DiagnosisAgentContext,
        evidence: list[str],
        metrics: dict[str, float],
    ) -> RemediationPlan:
        if not self._has_signal(
            metrics,
            evidence,
            [
                "cache.concurrent.rebuild.count",
                "cache.rebuild.count",
                "cache.db.query.count",
                "cache.hot.key.miss.count",
            ],
            ["cache breakdown", "缓存击穿", "rebuild", "hot key", "mutex", "singleflight"],
        ):
            return self._unsupported_with_warning(context, "CACHE_BREAKDOWN", evidence, "insufficient cache breakdown evidence")

        actions = [
            RemediationAction(
                action_type="ENABLE_CACHE_MUTEX",
                description="Enable hot-key cache rebuild mutex.",
                parameter_patch={"enableMutex": True},
                rationale="The Java scenario reduces duplicate DB queries and concurrent rebuilds when enableMutex is true.",
            )
        ]
        text = " ".join(evidence).lower()
        if "logical expire" in text or "logical_expire" in text or "逻辑过期" in text:
            actions.append(
                RemediationAction(
                    action_type="ENABLE_LOGICAL_EXPIRE",
                    description="Enable logical expire for hot-key reads.",
                    parameter_patch={"enableLogicalExpire": True},
                    rationale="The Java scenario serves hot-key requests from cache and performs one rebuild when enableLogicalExpire is true.",
                )
            )

        return RemediationPlan(
            experiment_id=context.experiment_id,
            scenario_code="CACHE_BREAKDOWN",
            actions=actions,
            expected_effects=[
                ExpectedMetricEffect(
                    metric_name="cache.concurrent.rebuild.count",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Concurrent cache rebuild pressure should fall.",
                ),
                ExpectedMetricEffect(
                    metric_name="cache.rebuild.count",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Duplicate rebuilds should be reduced.",
                ),
                ExpectedMetricEffect(
                    metric_name="cache.db.query.count",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Hot-key DB queries should be reduced.",
                ),
            ],
            evidence=evidence,
            confidence=self._confidence(context, 0.76),
        )

    def _plan_db_connection_pool(
        self,
        context: DiagnosisAgentContext,
        evidence: list[str],
        metrics: dict[str, float],
    ) -> RemediationPlan:
        if not self._has_signal(
            metrics,
            evidence,
            [
                "db.connection.acquire.timeout.count",
                "db.connection.acquire.avg.ms",
                "db.connection.hold.avg.ms",
                "api.error.count",
            ],
            ["connection pool", "acquire timeout", "连接池", "连接超时", "hold"],
        ):
            return self._unsupported_with_warning(
                context,
                "DB_CONNECTION_POOL_EXHAUSTION",
                evidence,
                "insufficient DB connection pool exhaustion evidence",
            )

        return RemediationPlan(
            experiment_id=context.experiment_id,
            scenario_code="DB_CONNECTION_POOL_EXHAUSTION",
            actions=[
                RemediationAction(
                    action_type="ENABLE_FAST_RELEASE",
                    description="Enable fast connection release simulation.",
                    parameter_patch={"enableFastRelease": True},
                    rationale="The Java scenario shortens effective connection hold time when enableFastRelease is true.",
                )
            ],
            expected_effects=[
                ExpectedMetricEffect(
                    metric_name="db.connection.acquire.timeout.count",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Fewer requests should time out while acquiring a connection.",
                ),
                ExpectedMetricEffect(
                    metric_name="db.connection.acquire.avg.ms",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Average connection acquire latency should fall.",
                ),
                ExpectedMetricEffect(
                    metric_name="db.connection.hold.avg.ms",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Average connection hold time should fall.",
                ),
            ],
            evidence=evidence,
            confidence=self._confidence(context, 0.74),
        )

    def _plan_downstream_timeout(
        self,
        context: DiagnosisAgentContext,
        evidence: list[str],
        metrics: dict[str, float],
    ) -> RemediationPlan:
        if not self._has_signal(
            metrics,
            evidence,
            ["downstream.timeout.count", "downstream.timeout.rate", "api.error.count"],
            ["downstream timeout", "timeout", "fallback", "下游", "超时"],
        ):
            return self._unsupported_with_warning(
                context,
                "DOWNSTREAM_TIMEOUT",
                evidence,
                "insufficient downstream timeout evidence",
            )

        return RemediationPlan(
            experiment_id=context.experiment_id,
            scenario_code="DOWNSTREAM_TIMEOUT",
            actions=[
                RemediationAction(
                    action_type="ENABLE_FALLBACK",
                    description="Enable fallback for downstream timeout responses.",
                    parameter_patch={"enableFallback": True},
                    rationale="The Java scenario converts timeout-driven API errors to fallback responses when enableFallback is true.",
                )
            ],
            expected_effects=[
                ExpectedMetricEffect(
                    metric_name="api.error.count",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Timeouts should no longer surface as API errors when fallback is enabled.",
                ),
                ExpectedMetricEffect(
                    metric_name="downstream.fallback.count",
                    direction=ExpectedMetricDirection.INCREASE,
                    description="Fallback executions should increase for timed-out downstream calls.",
                ),
                ExpectedMetricEffect(
                    metric_name="downstream.timeout.count",
                    direction=ExpectedMetricDirection.STABLE,
                    description="Fallback does not make the downstream dependency faster in the current Java simulation.",
                ),
            ],
            evidence=evidence,
            confidence=self._confidence(context, 0.72),
            warnings=["enableFallback is a degradation proposal; it does not reduce downstream.timeout.count"],
        )

    def _plan_retry_storm(
        self,
        context: DiagnosisAgentContext,
        evidence: list[str],
        metrics: dict[str, float],
    ) -> RemediationPlan:
        if not self._has_signal(
            metrics,
            evidence,
            [
                "downstream.retry.count",
                "downstream.retry.rate",
                "downstream.retry.amplification.factor",
                "downstream.total.call.count",
            ],
            ["retry storm", "retry amplification", "重试", "jitter", "retry budget"],
        ):
            return self._unsupported_with_warning(context, "RETRY_STORM", evidence, "insufficient retry storm evidence")

        return RemediationPlan(
            experiment_id=context.experiment_id,
            scenario_code="RETRY_STORM",
            actions=[
                RemediationAction(
                    action_type="ENABLE_RETRY_LIMIT",
                    description="Enable retry limiting.",
                    parameter_patch={"enableRetryLimit": True},
                    rationale="The Java scenario caps effective retries when enableRetryLimit is true.",
                ),
                RemediationAction(
                    action_type="ENABLE_RETRY_JITTER",
                    description="Enable retry jitter.",
                    parameter_patch={"enableJitter": True},
                    rationale="The Java scenario reduces per-retry latency when enableJitter is true.",
                ),
            ],
            expected_effects=[
                ExpectedMetricEffect(
                    metric_name="downstream.retry.count",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Retry traffic should be reduced by the retry limit.",
                ),
                ExpectedMetricEffect(
                    metric_name="downstream.retry.amplification.factor",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Retry amplification should fall as retry traffic is capped.",
                ),
                ExpectedMetricEffect(
                    metric_name="api.avg.latency.ms",
                    direction=ExpectedMetricDirection.DECREASE,
                    description="Jitter and fewer retry attempts should reduce average API latency in the simulation.",
                ),
                ExpectedMetricEffect(
                    metric_name="downstream.retry.exhausted.count",
                    direction=ExpectedMetricDirection.STABLE,
                    description="The current Java simulation derives exhausted count from initial failures, not retry policy.",
                ),
            ],
            evidence=evidence,
            confidence=self._confidence(context, 0.78),
        )

    def _unsupported_with_warning(
        self,
        context: DiagnosisAgentContext,
        scenario_code: str,
        evidence: list[str],
        warning: str,
    ) -> RemediationPlan:
        return RemediationPlan(
            experiment_id=context.experiment_id,
            scenario_code=scenario_code,
            status=RemediationPlanStatus.UNSUPPORTED,
            evidence=evidence,
            confidence=0.0,
            warnings=[warning],
        )

    def _scenario_code(self, context: DiagnosisAgentContext) -> str:
        request = context.evidence_package
        if context.analysis_result and context.analysis_result.get("faultType"):
            return str(context.analysis_result["faultType"]).upper()
        if request.rule_result and request.rule_result.fault_type:
            return request.rule_result.fault_type.upper()
        return (request.experiment.scenario_code or "UNKNOWN").upper()

    def _metric_values(self, context: DiagnosisAgentContext) -> dict[str, float]:
        values: dict[str, float] = {}
        for metric in context.evidence_package.metrics:
            try:
                values[metric.metric_name] = float(metric.metric_value)
            except (TypeError, ValueError):
                continue
        return values

    def _collect_evidence(self, context: DiagnosisAgentContext) -> list[str]:
        request = context.evidence_package
        evidence: list[str] = []
        if request.rule_result:
            if request.rule_result.reason:
                evidence.append(request.rule_result.reason)
            evidence.extend(request.rule_result.evidence or [])
            evidence.extend(request.rule_result.suggestions or [])
        if context.report:
            evidence.extend(context.report.evidence or [])
            evidence.extend(context.report.suggestions or [])
        for metric in request.metrics:
            evidence.append(f"{metric.metric_name}={metric.metric_value}")
        return evidence[:40]

    def _has_signal(
        self,
        metrics: dict[str, float],
        evidence: list[str],
        metric_names: list[str],
        text_terms: list[str],
    ) -> bool:
        if any(metrics.get(name, 0.0) > 0.0 for name in metric_names):
            return True
        evidence_text = " ".join(evidence).lower()
        return any(term.lower() in evidence_text for term in text_terms)

    def _confidence(self, context: DiagnosisAgentContext, default: float) -> float:
        request = context.evidence_package
        confidence = request.rule_result.confidence if request.rule_result else default
        if context.report and context.report.confidence:
            confidence = max(confidence, context.report.confidence)
        return max(0.0, min(1.0, confidence or default))
