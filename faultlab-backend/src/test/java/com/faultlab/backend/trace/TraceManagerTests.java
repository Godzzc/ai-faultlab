package com.faultlab.backend.trace;

import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.manager.TraceManager;
import com.faultlab.backend.trace.model.TraceSpanRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceManagerTests {

    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceManager traceManager = new TraceManager(traceSpanMapper);

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldCreateRootTraceContext() {
        TraceSpanRecord rootSpan = traceManager.startRootSpan(
                "experiment-1",
                "runScenario",
                "scenario",
                "{\"faultType\":\"mq_backlog\"}"
        );

        TraceContext traceContext = TraceContextHolder.get();

        assertThat(rootSpan.getTraceId()).isNotBlank();
        assertThat(rootSpan.getSpanId()).isNotBlank();
        assertThat(rootSpan.getParentSpanId()).isNull();
        assertThat(rootSpan.getExperimentId()).isEqualTo("experiment-1");
        assertThat(rootSpan.getOperationName()).isEqualTo("runScenario");
        assertThat(rootSpan.getComponent()).isEqualTo("scenario");
        assertThat(rootSpan.getTagsJson()).isEqualTo("{\"faultType\":\"mq_backlog\"}");
        assertThat(traceContext).isNotNull();
        assertThat(traceContext.getTraceId()).isEqualTo(rootSpan.getTraceId());
        assertThat(traceContext.getSpanId()).isEqualTo(rootSpan.getSpanId());
        assertThat(traceContext.getExperimentId()).isEqualTo(rootSpan.getExperimentId());
    }

    @Test
    void shouldCreateChildSpanWithParentSpanId() {
        TraceSpanRecord rootSpan = traceManager.startRootSpan("experiment-1", "root", "scenario");

        TraceSpanRecord childSpan = traceManager.startChildSpan("saveTrace", "trace");

        assertThat(childSpan.getTraceId()).isEqualTo(rootSpan.getTraceId());
        assertThat(childSpan.getSpanId()).isNotBlank();
        assertThat(childSpan.getSpanId()).isNotEqualTo(rootSpan.getSpanId());
        assertThat(childSpan.getParentSpanId()).isEqualTo(rootSpan.getSpanId());
        assertThat(childSpan.getExperimentId()).isEqualTo("experiment-1");
        assertThat(TraceContextHolder.get().getSpanId()).isEqualTo(childSpan.getSpanId());
    }

    @Test
    void finishSpanShouldCalculateDurationAndPersist() {
        TraceSpanRecord spanRecord = traceManager.startRootSpan("experiment-1", "root", "scenario");

        traceManager.finishSpan(spanRecord);

        ArgumentCaptor<TraceSpan> captor = ArgumentCaptor.forClass(TraceSpan.class);
        verify(traceSpanMapper).insert(captor.capture());
        TraceSpan persistedSpan = captor.getValue();

        assertThat(spanRecord.getEndTime()).isNotNull();
        assertThat(spanRecord.getDurationMs()).isNotNegative();
        assertThat(spanRecord.getStatus()).isEqualTo(TraceManager.STATUS_SUCCESS);
        assertThat(persistedSpan.getTraceId()).isEqualTo(spanRecord.getTraceId());
        assertThat(persistedSpan.getSpanId()).isEqualTo(spanRecord.getSpanId());
        assertThat(persistedSpan.getExperimentId()).isEqualTo("experiment-1");
        assertThat(persistedSpan.getDurationMs()).isEqualTo(spanRecord.getDurationMs());
        assertThat(persistedSpan.getStatus()).isEqualTo(TraceManager.STATUS_SUCCESS);
    }

    @Test
    void shouldNotThrowWhenTracePersistFailed() {
        TraceSpanRecord spanRecord = traceManager.startRootSpan("experiment-1", "root", "scenario");
        when(traceSpanMapper.insert(any(TraceSpan.class))).thenThrow(new RuntimeException("database unavailable"));

        assertThatCode(() -> traceManager.finishSpan(spanRecord))
                .doesNotThrowAnyException();

        assertThat(spanRecord.getStatus()).isEqualTo(TraceManager.STATUS_SUCCESS);
        assertThat(spanRecord.getEndTime()).isNotNull();
    }
}
