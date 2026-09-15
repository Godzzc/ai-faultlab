from app.agent.tools.base import AgentTool
from app.agent.tools.evidence_tool import EvidenceCollectionTool
from app.agent.tools.report_tool import DiagnosisReportGeneratorTool
from app.agent.tools.retrieval_tool import RunbookRetrievalTool
from app.agent.tools.validation_tool import RunbookReferenceValidationTool


class AgentToolRegistryError(Exception):
    pass


class AgentToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, AgentTool] = {}

    def register(self, tool: AgentTool) -> None:
        if tool.name in self._tools:
            raise AgentToolRegistryError(f"Agent tool already registered: {tool.name}")
        self._tools[tool.name] = tool

    def get(self, tool_name: str) -> AgentTool:
        tool = self._tools.get(tool_name)
        if not tool:
            raise AgentToolRegistryError(f"Agent tool not found: {tool_name}")
        return tool

    def list_tools(self) -> list[AgentTool]:
        return list(self._tools.values())


def build_default_tool_registry(
    retrieval_service=None,
    runbook_chunk_loader=None,
    llm_client=None,
    prompt_builder=None,
    model_router=None,
) -> AgentToolRegistry:
    registry = AgentToolRegistry()
    registry.register(EvidenceCollectionTool())
    registry.register(RunbookRetrievalTool(retrieval_service=retrieval_service))
    registry.register(RunbookReferenceValidationTool(runbook_chunk_loader=runbook_chunk_loader))
    registry.register(
        DiagnosisReportGeneratorTool(
            llm_client=llm_client,
            prompt_builder=prompt_builder,
            model_router=model_router,
        )
    )
    return registry
