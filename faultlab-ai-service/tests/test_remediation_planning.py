from app import workflow
from app.agent.models import AgentToolCallStatus, DiagnosisAgentContext
from app.agent.orchestrator import DiagnosisAgentOrchestrator
from app.agent.run_store import AgentRunStore
from app.agent.state import DiagnosisAgentState
from app.agent.tool_registry import AgentToolRegistry
from app.agent.tools.remediation_planning_tool import RemediationPlanningTool
from app.remediation.models import RemediationPlan, RemediationPlanStatus
from app.remediation.planner import RemediationPlanner
from app.remediation.policy import RemediationPolicyErrorCode, validate_patch
from app.schemas import DiagnosisRequest


def build_request(scenario_code: str, metrics: dict[str, str] | None = None) -> DiagnosisRequest:
    metrics = metrics or {}
    return DiagnosisRequest.model_validate({
        "requestId": f"remediation-{scenario_code.lower()}",
        "experiment": {
            "experimentId": "exp_remediation",
            "scenarioCode": scenario_code,
            "status": "COMPLETED",
            "traceId": "trace_remediation",
        },
        "metrics": [
            {
                "metricName": name,
                "metricValue": value,
                "metricUnit": "count",
                "component": "test",
            }
            for name, value in metrics.items()
        ],
        "traceTree": {"traceId": "trace_remediation", "roots": []},
        "ruleResult": {
            "experimentId": "exp_remediation",
            "faultType": scenario_code,
            "faultName": scenario_code,
            "confidence": 0.82,
            "matched": True,
            "reason": f"{scenario_code} detected by rule diagnosis",
            "evidence": [f"{name}={value}" for name, value in metrics.items()],
            "suggestions": ["Generate a structured remediation proposal."],
        },
    })


def plan_for(scenario_code: str, metrics: dict[str, str]) -> RemediationPlan:
    context = DiagnosisAgentContext.from_request(build_request(scenario_code, metrics))
    context.analysis_result = {
        "faultType": scenario_code,
        "metricCount": len(metrics),
        "traceSummary": {},
        "ruleMatched": True,
    }
    return RemediationPlanner().plan(context)


def test_remediation_plan_can_be_created():
    plan = RemediationPlan(experiment_id="exp", scenario_code="CACHE_BREAKDOWN")

    assert plan.scenario_code == "CACHE_BREAKDOWN"
    assert plan.status == RemediationPlanStatus.PROPOSED


def test_plan_id_is_generated():
    first = RemediationPlan(scenario_code="CACHE_BREAKDOWN")
    second = RemediationPlan(scenario_code="CACHE_BREAKDOWN")

    assert first.plan_id
    assert second.plan_id
    assert first.plan_id != second.plan_id


def test_cache_breakdown_generates_enable_cache_mutex():
    plan = plan_for("CACHE_BREAKDOWN", {"cache.concurrent.rebuild.count": "20"})

    assert plan.status == RemediationPlanStatus.PROPOSED
    assert "ENABLE_CACHE_MUTEX" in [action.action_type for action in plan.actions]
    assert {"enableMutex": True} in [action.parameter_patch for action in plan.actions]


def test_db_connection_pool_generates_enable_fast_release():
    plan = plan_for("DB_CONNECTION_POOL_EXHAUSTION", {"db.connection.acquire.timeout.count": "90"})

    assert "ENABLE_FAST_RELEASE" in [action.action_type for action in plan.actions]
    assert {"enableFastRelease": True} in [action.parameter_patch for action in plan.actions]


def test_downstream_timeout_generates_enable_fallback():
    plan = plan_for("DOWNSTREAM_TIMEOUT", {"api.error.count": "80", "downstream.timeout.count": "80"})

    assert "ENABLE_FALLBACK" in [action.action_type for action in plan.actions]
    assert {"enableFallback": True} in [action.parameter_patch for action in plan.actions]


def test_retry_storm_generates_enable_retry_limit():
    plan = plan_for("RETRY_STORM", {"downstream.retry.amplification.factor": "4"})

    assert "ENABLE_RETRY_LIMIT" in [action.action_type for action in plan.actions]
    assert {"enableRetryLimit": True} in [action.parameter_patch for action in plan.actions]


def test_retry_storm_generates_enable_retry_jitter():
    plan = plan_for("RETRY_STORM", {"downstream.retry.count": "210"})

    assert "ENABLE_RETRY_JITTER" in [action.action_type for action in plan.actions]
    assert {"enableJitter": True} in [action.parameter_patch for action in plan.actions]


def test_expected_effects_use_real_metric_names():
    plan = plan_for("RETRY_STORM", {"downstream.retry.count": "210"})
    metric_names = {effect.metric_name for effect in plan.expected_effects}

    assert "downstream.retry.count" in metric_names
    assert "downstream.retry.amplification.factor" in metric_names
    assert "api.avg.latency.ms" in metric_names


def test_policy_rejects_unknown_parameter():
    errors = validate_patch("CACHE_BREAKDOWN", {"unknown": True})

    assert errors[0].error_code == RemediationPolicyErrorCode.UNKNOWN_REMEDIATION_PARAMETER


def test_policy_rejects_wrong_parameter_type():
    errors = validate_patch("CACHE_BREAKDOWN", {"enableMutex": "true"})

    assert errors[0].error_code == RemediationPolicyErrorCode.INVALID_REMEDIATION_PARAMETER_TYPE


def test_policy_disallows_scenario_code_patch():
    errors = validate_patch("CACHE_BREAKDOWN", {"scenarioCode": "RETRY_STORM"})

    assert errors[0].error_code == RemediationPolicyErrorCode.UNKNOWN_REMEDIATION_PARAMETER


def test_unsupported_scenario_returns_unsupported():
    plan = plan_for("MQ_BACKLOG", {"backlogCount": "100"})

    assert plan.status == RemediationPlanStatus.UNSUPPORTED
    assert not plan.actions


def test_remediation_planning_tool_returns_agent_tool_result():
    context = DiagnosisAgentContext.from_request(
        build_request("DOWNSTREAM_TIMEOUT", {"api.error.count": "10", "downstream.timeout.count": "10"})
    )
    context.analysis_result = {"faultType": "DOWNSTREAM_TIMEOUT"}

    result = RemediationPlanningTool().execute(context)

    assert result.success is True
    assert result.tool_name == "remediation_planning"
    assert result.data["remediationPlan"].status == RemediationPlanStatus.PROPOSED


def test_remediation_tool_call_record_is_recorded():
    context = DiagnosisAgentContext.from_request(
        build_request("CACHE_BREAKDOWN", {"cache.concurrent.rebuild.count": "10"})
    )
    context.analysis_result = {"faultType": "CACHE_BREAKDOWN"}
    orchestrator = DiagnosisAgentOrchestrator(run_store=AgentRunStore())
    registry = AgentToolRegistry()
    registry.register(RemediationPlanningTool())

    result = orchestrator.execute_tool(
        "remediation_planning",
        context,
        DiagnosisAgentState.REMEDIATION_PLAN,
        registry,
    )

    assert result.success is True
    assert context.tool_calls[0].tool_name == "remediation_planning"
    assert context.tool_calls[0].status == AgentToolCallStatus.SUCCESS


def test_orchestrator_stores_remediation_plan(monkeypatch):
    monkeypatch.setattr(workflow.settings, "llm_enabled", False)
    monkeypatch.setattr(workflow.settings, "dashscope_api_key", "test-key")
    store = AgentRunStore()
    orchestrator = DiagnosisAgentOrchestrator(run_store=store)

    orchestrator.run(build_request("RETRY_STORM", {"downstream.retry.count": "210"}))
    run = store.get("remediation-retry_storm")

    assert run is not None
    assert run.remediation_plan is not None
    assert run.remediation_plan.status == RemediationPlanStatus.PROPOSED
