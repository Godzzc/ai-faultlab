from abc import ABC, abstractmethod

from app.agent.models import DiagnosisAgentContext
from app.agent.tools.models import AgentToolResult


class AgentTool(ABC):
    @property
    @abstractmethod
    def name(self) -> str:
        raise NotImplementedError

    @property
    @abstractmethod
    def description(self) -> str:
        raise NotImplementedError

    @abstractmethod
    def execute(self, context: DiagnosisAgentContext) -> AgentToolResult:
        raise NotImplementedError
