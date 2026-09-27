package com.faultlab.backend.common;

public enum ErrorCode {
    SUCCESS(0, "success"),
    PARAM_ERROR(400, "param error"),
    NOT_FOUND(404, "not found"),
    INTERNAL_ERROR(500, "internal error"),
    AI_SERVICE_ERROR(1001, "ai service error"),
    TRACE_ERROR(2001, "trace error"),
    SCENARIO_EXECUTE_ERROR(3001, "scenario execute error"),
    ORIGINAL_EXPERIMENT_NOT_FOUND(4001, "original experiment not found"),
    ORIGINAL_EXPERIMENT_PARAMS_UNAVAILABLE(4002, "original experiment params unavailable"),
    REMEDIATION_REPLAY_UNSUPPORTED_SCENARIO(4003, "remediation replay unsupported scenario"),
    INVALID_REMEDIATION_PATCH(4004, "invalid remediation patch"),
    REMEDIATION_PARAMETER_NOT_ALLOWED(4005, "remediation parameter not allowed"),
    SCENARIO_MISMATCH(4006, "scenario mismatch"),
    REPLAY_EXECUTION_FAILED(4007, "replay execution failed");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
