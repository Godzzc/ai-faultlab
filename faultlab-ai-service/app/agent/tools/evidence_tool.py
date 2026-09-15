from app.agent.models import DiagnosisAgentContext
from app.agent.tools.base import AgentTool
from app.agent.tools.models import AgentToolResult


class EvidenceCollectionTool(AgentTool):
    @property
    def name(self) -> str:
        return "evidence_collection"

    @property
    def description(self) -> str:
        return "Normalize the EvidencePackage fields already supplied by the Java backend."

    def execute(self, context: DiagnosisAgentContext) -> AgentToolResult:
        request = context.evidence_package
        rule_result = request.rule_result
        fault_type = (rule_result.fault_type if rule_result else "") or request.experiment.scenario_code or "UNKNOWN"
        experiment_id = request.experiment.experiment_id or (rule_result.experiment_id if rule_result else "") or None
        trace_available = bool(request.trace_tree.trace_id or request.trace_tree.roots)
        warnings: list[str] = []
        if not rule_result:
            warnings.append("ruleResult is missing; report may rely on experiment and metric evidence only")

        return AgentToolResult.ok(
            tool_name=self.name,
            data={
                "experimentId": experiment_id,
                "faultType": fault_type,
                "metricCount": len(request.metrics),
                "traceAvailable": trace_available,
                "ruleDiagnosisAvailable": bool(rule_result),
            },
            warnings=warnings,
        )
