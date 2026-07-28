package com.faultlab.backend.trace.context;

public class TraceContextSnapshot {

    private final String traceId;
    private final String parentSpanId;
    private final String experimentId;

    public TraceContextSnapshot(String traceId, String parentSpanId, String experimentId) {
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.experimentId = experimentId;
    }

    public static TraceContextSnapshot from(TraceContext traceContext) {
        if (traceContext == null) {
            return null;
        }
        return new TraceContextSnapshot(
                traceContext.getTraceId(),
                traceContext.getSpanId(),
                traceContext.getExperimentId()
        );
    }

    public String getTraceId() {
        return traceId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public TraceContext toTraceContext() {
        return new TraceContext(traceId, parentSpanId, experimentId);
    }
}
