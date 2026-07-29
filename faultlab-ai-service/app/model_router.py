import logging
from dataclasses import dataclass
from typing import Any

from app.config import settings
from app.schemas import DiagnosisRequest

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class ModelRouteResult:
    model: str
    reason: str


class ModelRouter:
    def select_model(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> ModelRouteResult:
        try:
            route_result = self._select_model(request, trace_summary)
            logger.info(
                "Selected LLM model=%s route_reason=%s",
                route_result.model,
                route_result.reason,
            )
            return route_result
        except Exception as exc:
            logger.warning("Model routing failed, using default model: %s", exc)
            return ModelRouteResult(
                model=settings.llm_default_model,
                reason="router_error_default",
            )

    def _select_model(
        self,
        request: DiagnosisRequest,
        trace_summary: dict[str, Any],
    ) -> ModelRouteResult:
        rule_result = request.rule_result
        if not rule_result or not rule_result.matched:
            return ModelRouteResult(settings.llm_fast_model, "rule_result_missing_or_not_matched")

        node_count = int(trace_summary.get("nodeCount") or trace_summary.get("spanCount") or 0)
        if node_count >= 30:
            return ModelRouteResult(settings.llm_reasoning_model, "large_trace_tree")

        if len(rule_result.evidence or []) >= 10:
            return ModelRouteResult(settings.llm_reasoning_model, "rich_rule_evidence")

        if len(request.metrics or []) >= 20:
            return ModelRouteResult(settings.llm_long_context_model, "many_metrics")

        return ModelRouteResult(settings.llm_default_model, "default_diagnosis")
