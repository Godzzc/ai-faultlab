package com.faultlab.backend.trace;

import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextHolderTests {

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldClearTraceContext() {
        TraceContext traceContext = new TraceContext("trace-1", "span-1", "experiment-1");

        TraceContextHolder.set(traceContext);
        assertThat(TraceContextHolder.get()).isSameAs(traceContext);

        TraceContextHolder.clear();

        assertThat(TraceContextHolder.get()).isNull();
    }
}
