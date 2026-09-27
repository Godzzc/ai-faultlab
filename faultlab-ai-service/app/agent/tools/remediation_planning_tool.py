from app.agent.models import DiagnosisAgentContext
from app.agent.tools.base import AgentTool
from app.agent.tools.models import AgentToolResult
from app.remediation.models import RemediationPlanStatus
from app.remediation.planner import RemediationPlanner


class RemediationPlanningTool(AgentTool):
    def __init__(self, planner: RemediationPlanner | None = None) -> None:
        self.planner = planner or RemediationPlanner()

    @property
    def name(self) -> str:
        return "remediation_planning"

    @property
    def description(self) -> str:
        return "Generate a structured remediation proposal without executing it."

    def execute(self, context: DiagnosisAgentContext) -> AgentToolResult:
        try:
            plan = self.planner.plan(context)
        except Exception as exc:
            return AgentToolResult.fail(
                tool_name=self.name,
                error_code="REMEDIATION_PLANNING_FAILED",
                error_message=str(exc),
            )

        warnings = list(plan.warnings)
        metadata = {
            "planStatus": plan.status,
            "actionCount": len(plan.actions),
        }
        if plan.status == RemediationPlanStatus.UNSUPPORTED:
            return AgentToolResult.ok(
                tool_name=self.name,
                data={"remediationPlan": plan},
                warnings=warnings,
                metadata=metadata,
            )
        if plan.status == RemediationPlanStatus.INVALID:
            return AgentToolResult.fail(
                tool_name=self.name,
                error_code="REMEDIATION_PLANNING_FAILED",
                error_message="Generated remediation plan failed policy validation",
                data={"remediationPlan": plan},
                warnings=warnings,
                metadata=metadata,
            )
        return AgentToolResult.ok(
            tool_name=self.name,
            data={"remediationPlan": plan},
            warnings=warnings,
            metadata=metadata,
        )
