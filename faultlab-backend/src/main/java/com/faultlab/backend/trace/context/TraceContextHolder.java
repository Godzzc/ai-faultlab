package com.faultlab.backend.trace.context;

public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    public static void set(TraceContext traceContext) {
        CONTEXT.set(traceContext);
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
