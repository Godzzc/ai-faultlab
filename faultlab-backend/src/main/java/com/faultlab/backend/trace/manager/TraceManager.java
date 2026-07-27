package com.faultlab.backend.trace.manager;

import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.model.TraceSpanRecord;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TraceManager {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";

    private static final Logger log = LoggerFactory.getLogger(TraceManager.class);

    private final TraceSpanMapper traceSpanMapper;

    public TraceManager(TraceSpanMapper traceSpanMapper) {
        this.traceSpanMapper = traceSpanMapper;
    }

    public TraceSpanRecord startRootSpan(String experimentId, String operationName, String component) {
        return startRootSpan(experimentId, operationName, component, null);
    }

    public TraceSpanRecord startRootSpan(String experimentId, String operationName, String component, String tagsJson) {
        TraceSpanRecord spanRecord = createSpanRecord(
                createTraceId(),
                createSpanId(),
                null,
                experimentId,
                operationName,
                component,
                tagsJson
        );
        TraceContextHolder.set(new TraceContext(
                spanRecord.getTraceId(),
                spanRecord.getSpanId(),
                spanRecord.getExperimentId()
        ));
        return spanRecord;
    }

    public TraceSpanRecord startChildSpan(String operationName, String component) {
        return startChildSpan(operationName, component, null);
    }

    public TraceSpanRecord startChildSpan(String operationName, String component, String tagsJson) {
        TraceContext parentContext = TraceContextHolder.get();
        if (parentContext == null) {
            return startRootSpan(null, operationName, component, tagsJson);
        }

        TraceSpanRecord spanRecord = createSpanRecord(
                parentContext.getTraceId(),
                createSpanId(),
                parentContext.getSpanId(),
                parentContext.getExperimentId(),
                operationName,
                component,
                tagsJson
        );
        TraceContextHolder.set(new TraceContext(
                spanRecord.getTraceId(),
                spanRecord.getSpanId(),
                spanRecord.getExperimentId()
        ));
        return spanRecord;
    }

    public void finishSpan(TraceSpanRecord spanRecord) {
        finishSpan(spanRecord, STATUS_SUCCESS, null);
    }

    public void finishSpan(TraceSpanRecord spanRecord, Throwable throwable) {
        String errorMessage = throwable == null ? null : throwable.getMessage();
        finishSpan(spanRecord, STATUS_ERROR, errorMessage);
    }

    public void finishSpan(TraceSpanRecord spanRecord, String status, String errorMessage) {
        if (spanRecord == null) {
            return;
        }

        LocalDateTime endTime = LocalDateTime.now();
        spanRecord.setEndTime(endTime);
        spanRecord.setDurationMs(Duration.between(spanRecord.getStartTime(), endTime).toMillis());
        spanRecord.setStatus(status);
        spanRecord.setErrorMessage(errorMessage);

        persistSpan(spanRecord);
    }

    private TraceSpanRecord createSpanRecord(
            String traceId,
            String spanId,
            String parentSpanId,
            String experimentId,
            String operationName,
            String component,
            String tagsJson
    ) {
        TraceSpanRecord spanRecord = new TraceSpanRecord();
        spanRecord.setTraceId(traceId);
        spanRecord.setSpanId(spanId);
        spanRecord.setParentSpanId(parentSpanId);
        spanRecord.setExperimentId(experimentId);
        spanRecord.setOperationName(operationName);
        spanRecord.setComponent(component);
        spanRecord.setStartTime(LocalDateTime.now());
        spanRecord.setStatus(STATUS_SUCCESS);
        spanRecord.setTagsJson(tagsJson);
        return spanRecord;
    }

    private void persistSpan(TraceSpanRecord spanRecord) {
        try {
            traceSpanMapper.insert(toEntity(spanRecord));
        } catch (Exception exception) {
            log.warn("Failed to persist trace span. traceId={}, spanId={}",
                    spanRecord.getTraceId(), spanRecord.getSpanId(), exception);
        }
    }

    private TraceSpan toEntity(TraceSpanRecord spanRecord) {
        TraceSpan traceSpan = new TraceSpan();
        traceSpan.setTraceId(spanRecord.getTraceId());
        traceSpan.setSpanId(spanRecord.getSpanId());
        traceSpan.setParentSpanId(spanRecord.getParentSpanId());
        traceSpan.setExperimentId(spanRecord.getExperimentId());
        traceSpan.setOperationName(spanRecord.getOperationName());
        traceSpan.setComponent(spanRecord.getComponent());
        traceSpan.setStartTime(spanRecord.getStartTime());
        traceSpan.setEndTime(spanRecord.getEndTime());
        traceSpan.setDurationMs(spanRecord.getDurationMs());
        traceSpan.setStatus(spanRecord.getStatus());
        traceSpan.setErrorMessage(spanRecord.getErrorMessage());
        traceSpan.setTagsJson(spanRecord.getTagsJson());
        traceSpan.setCreatedAt(LocalDateTime.now());
        return traceSpan;
    }

    private String createTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String createSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
