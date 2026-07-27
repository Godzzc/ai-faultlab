package com.faultlab.backend.trace.context;

public class TraceContext {

    private final String traceId;
    private final String spanId;
    private final String experimentId;

    public TraceContext(String traceId, String spanId, String experimentId) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.experimentId = experimentId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getExperimentId() {
        return experimentId;
    }
}
