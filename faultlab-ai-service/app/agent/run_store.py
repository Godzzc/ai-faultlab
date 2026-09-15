from collections import OrderedDict
from threading import Lock

from app.agent.models import AgentRunSummary, DiagnosisAgentContext


class AgentRunStore:
    def __init__(self, max_size: int = 100) -> None:
        self.max_size = max_size
        self._runs: OrderedDict[str, AgentRunSummary] = OrderedDict()
        self._lock = Lock()

    def save(self, context: DiagnosisAgentContext) -> None:
        summary = context.to_summary()
        with self._lock:
            self._runs[summary.request_id] = summary
            self._runs.move_to_end(summary.request_id)
            while len(self._runs) > self.max_size:
                self._runs.popitem(last=False)

    def get(self, request_id: str) -> AgentRunSummary | None:
        with self._lock:
            return self._runs.get(request_id)

    def list(self) -> list[AgentRunSummary]:
        with self._lock:
            return list(reversed(self._runs.values()))


AGENT_RUN_STORE = AgentRunStore(max_size=100)
