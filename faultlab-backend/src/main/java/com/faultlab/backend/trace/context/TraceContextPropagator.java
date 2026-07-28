package com.faultlab.backend.trace.context;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.util.StringUtils;

public final class TraceContextPropagator {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceContextPropagator.class);

    private TraceContextPropagator() {
    }

    public static TraceContextSnapshot capture() {
        return TraceContextSnapshot.from(TraceContextHolder.get());
    }

    public static Runnable wrap(Runnable runnable) {
        TraceContextSnapshot snapshot = capture();
        return () -> runWith(snapshot, runnable);
    }

    public static void runWith(TraceContextSnapshot snapshot, Runnable runnable) {
        try {
            restore(snapshot);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to restore trace context before async execution", exception);
        }

        try {
            runnable.run();
        } finally {
            TraceContextHolder.clear();
        }
    }

    public static void restore(TraceContextSnapshot snapshot) {
        if (snapshot == null || !StringUtils.hasText(snapshot.getTraceId())
                || !StringUtils.hasText(snapshot.getParentSpanId())) {
            TraceContextHolder.clear();
            return;
        }
        TraceContextHolder.set(snapshot.toTraceContext());
    }

    public static MessagePostProcessor messagePostProcessor() {
        TraceContextSnapshot snapshot = capture();
        return message -> attachHeaders(message, snapshot);
    }

    public static Message attachHeaders(Message message, TraceContextSnapshot snapshot) {
        if (snapshot == null || message == null) {
            return message;
        }
        message.getMessageProperties().setHeader(TraceMessageHeaderNames.TRACE_ID, snapshot.getTraceId());
        message.getMessageProperties().setHeader(TraceMessageHeaderNames.PARENT_SPAN_ID, snapshot.getParentSpanId());
        message.getMessageProperties().setHeader(TraceMessageHeaderNames.EXPERIMENT_ID, snapshot.getExperimentId());
        return message;
    }

    public static TraceContextSnapshot fromHeaders(Map<String, Object> headers) {
        if (headers == null) {
            LOGGER.warn("Trace headers missing from MQ message");
            return null;
        }

        String traceId = asString(headers.get(TraceMessageHeaderNames.TRACE_ID));
        String parentSpanId = asString(headers.get(TraceMessageHeaderNames.PARENT_SPAN_ID));
        String experimentId = asString(headers.get(TraceMessageHeaderNames.EXPERIMENT_ID));
        if (!StringUtils.hasText(traceId) || !StringUtils.hasText(parentSpanId)) {
            LOGGER.warn("Trace headers incomplete from MQ message. traceId={}, parentSpanId={}", traceId, parentSpanId);
            return null;
        }
        return new TraceContextSnapshot(traceId, parentSpanId, experimentId);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
