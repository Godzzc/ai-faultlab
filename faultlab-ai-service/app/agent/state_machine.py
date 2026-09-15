import logging
from collections.abc import Callable
from datetime import UTC, datetime
from typing import TypeVar

from app.agent.models import AgentStageRecord, AgentStageStatus, DiagnosisAgentContext
from app.agent.state import ALLOWED_TRANSITIONS, TERMINAL_STATES, DiagnosisAgentState

logger = logging.getLogger(__name__)

T = TypeVar("T")


class InvalidAgentStateTransition(Exception):
    pass


class AgentStageError(Exception):
    def __init__(self, state: DiagnosisAgentState, error_code: str, message: str) -> None:
        super().__init__(message)
        self.state = state
        self.error_code = error_code
        self.message = message


class DiagnosisAgentStateMachine:
    def __init__(self, context: DiagnosisAgentContext) -> None:
        self.context = context

    def transition_to(self, next_state: DiagnosisAgentState) -> None:
        current_state = self.context.current_state
        allowed = ALLOWED_TRANSITIONS[current_state]
        if next_state not in allowed:
            raise InvalidAgentStateTransition(
                f"Illegal diagnosis agent transition: {current_state} -> {next_state}"
            )
        self.context.current_state = next_state
        logger.info(
            "DiagnosisAgent transition requestId=%s from=%s to=%s",
            self.context.request_id,
            current_state,
            next_state,
        )

    def run_stage(
        self,
        state: DiagnosisAgentState,
        handler: Callable[[], T],
        error_code: str = "AGENT_STAGE_FAILED",
    ) -> T:
        if self.context.current_state in TERMINAL_STATES:
            raise InvalidAgentStateTransition(
                f"Cannot run stage {state} from terminal state {self.context.current_state}"
            )

        self.transition_to(state)
        record = AgentStageRecord(
            state=state,
            started_at=datetime.now(UTC),
            status=AgentStageStatus.RUNNING,
        )
        self.context.stage_records.append(record)

        try:
            result = handler()
            record.status = AgentStageStatus.SUCCESS
            return result
        except Exception as exc:
            record.status = AgentStageStatus.FAILED
            record.error_code = error_code
            record.error_message = str(exc)
            self.fail(error_code, str(exc))
            raise AgentStageError(state, error_code, str(exc)) from exc
        finally:
            record.finished_at = datetime.now(UTC)
            if record.started_at and record.finished_at:
                record.duration_ms = int((record.finished_at - record.started_at).total_seconds() * 1000)

    def complete(self) -> None:
        self.transition_to(DiagnosisAgentState.COMPLETED)
        self.context.completed_at = datetime.now(UTC)

    def fail(self, error_code: str, error_message: str) -> None:
        if self.context.current_state != DiagnosisAgentState.FAILED:
            self.transition_to(DiagnosisAgentState.FAILED)
        self.context.error_code = error_code
        self.context.error_message = error_message
        self.context.completed_at = datetime.now(UTC)
