package com.faultlab.backend.trace.context;

public final class TraceMessageHeaderNames {

    public static final String TRACE_ID = "faultlab-trace-id";
    public static final String PARENT_SPAN_ID = "faultlab-parent-span-id";
    public static final String EXPERIMENT_ID = "faultlab-experiment-id";

    private TraceMessageHeaderNames() {
    }
}
