from enum import StrEnum


class DiagnosisAgentState(StrEnum):
    RECEIVED = "RECEIVED"
    COLLECT_EVIDENCE = "COLLECT_EVIDENCE"
    ANALYZE = "ANALYZE"
    RETRIEVE = "RETRIEVE"
    FALLBACK = "FALLBACK"
    VALIDATE = "VALIDATE"
    GENERATE_REPORT = "GENERATE_REPORT"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


TERMINAL_STATES = {
    DiagnosisAgentState.COMPLETED,
    DiagnosisAgentState.FAILED,
}


ALLOWED_TRANSITIONS: dict[DiagnosisAgentState, set[DiagnosisAgentState]] = {
    DiagnosisAgentState.RECEIVED: {DiagnosisAgentState.COLLECT_EVIDENCE, DiagnosisAgentState.FAILED},
    DiagnosisAgentState.COLLECT_EVIDENCE: {DiagnosisAgentState.ANALYZE, DiagnosisAgentState.FAILED},
    DiagnosisAgentState.ANALYZE: {DiagnosisAgentState.RETRIEVE, DiagnosisAgentState.FAILED},
    DiagnosisAgentState.RETRIEVE: {
        DiagnosisAgentState.VALIDATE,
        DiagnosisAgentState.FALLBACK,
        DiagnosisAgentState.FAILED,
    },
    DiagnosisAgentState.FALLBACK: {DiagnosisAgentState.GENERATE_REPORT, DiagnosisAgentState.FAILED},
    DiagnosisAgentState.VALIDATE: {DiagnosisAgentState.GENERATE_REPORT, DiagnosisAgentState.FAILED},
    DiagnosisAgentState.GENERATE_REPORT: {DiagnosisAgentState.COMPLETED, DiagnosisAgentState.FAILED},
    DiagnosisAgentState.COMPLETED: set(),
    DiagnosisAgentState.FAILED: set(),
}
