package com.faultlab.backend.scenario.downstream;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.manager.TraceManager;
import com.faultlab.backend.trace.model.TraceSpanRecord;
import java.util.Map;

final class DownstreamScenarioSupport {

    static final String COMPONENT = "Downstream";

    private DownstreamScenarioSupport() {
    }

    static int readInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return defaultValue;
    }

    static long readLong(Map<String, Object> params, String key, long defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return defaultValue;
    }

    static double readDouble(Map<String, Object> params, String key, double defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text);
        }
        return defaultValue;
    }

    static boolean readBoolean(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    static double clampRatio(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    static void record(MetricService metricService, String experimentId, String metricName, Number metricValue, String unit) {
        metricService.recordMetric(experimentId, metricName, metricValue, unit, COMPONENT);
    }

    static void trace(TraceManager traceManager, String operationName, Runnable operation) {
        TraceContext parent = TraceContextHolder.get();
        TraceSpanRecord span = traceManager.startChildSpan(operationName, COMPONENT);
        try {
            operation.run();
            traceManager.finishSpan(span);
        } catch (RuntimeException exception) {
            traceManager.finishSpan(span, exception);
            throw exception;
        } finally {
            if (parent == null) {
                TraceContextHolder.clear();
            } else {
                TraceContextHolder.set(parent);
            }
        }
    }
}
