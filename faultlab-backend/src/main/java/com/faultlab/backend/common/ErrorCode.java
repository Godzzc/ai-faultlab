package com.faultlab.backend.common;

public enum ErrorCode {
    SUCCESS(0, "success"),
    PARAM_ERROR(400, "param error"),
    NOT_FOUND(404, "not found"),
    INTERNAL_ERROR(500, "internal error"),
    AI_SERVICE_ERROR(1001, "ai service error"),
    TRACE_ERROR(2001, "trace error"),
    SCENARIO_EXECUTE_ERROR(3001, "scenario execute error");

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
