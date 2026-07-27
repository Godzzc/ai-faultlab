package com.faultlab.backend.trace;

import com.faultlab.backend.FaultLabBackendApplication;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = {FaultLabBackendApplication.class, TraceAspectTests.TraceAspectTestConfig.class})
class TraceAspectTests {

    @Autowired
    private TestTraceService testTraceService;

    @MockBean
    private TraceSpanMapper traceSpanMapper;

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
        reset(traceSpanMapper);
    }

    @Test
    void shouldCreateSpanWhenAnnotatedMethodSuccess() {
        String result = testTraceService.success();

        TraceSpan persistedSpan = capturePersistedSpan();
        assertThat(result).isEqualTo("ok");
        assertThat(persistedSpan.getTraceId()).isNotBlank();
        assertThat(persistedSpan.getSpanId()).isNotBlank();
        assertThat(persistedSpan.getParentSpanId()).isNull();
        assertThat(persistedSpan.getOperationName()).isEqualTo("successOperation");
        assertThat(persistedSpan.getComponent()).isEqualTo("trace-test");
        assertThat(persistedSpan.getTagsJson()).isEqualTo("{\"case\":\"success\"}");
        assertThat(persistedSpan.getStatus()).isEqualTo("SUCCESS");
        assertThat(persistedSpan.getErrorMessage()).isNull();
    }

    @Test
    void shouldRecordErrorWhenAnnotatedMethodThrowsException() {
        assertThatThrownBy(() -> testTraceService.fail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("business failed");

        TraceSpan persistedSpan = capturePersistedSpan();
        assertThat(persistedSpan.getOperationName()).isEqualTo("errorOperation");
        assertThat(persistedSpan.getStatus()).isEqualTo("ERROR");
        assertThat(persistedSpan.getErrorMessage()).isEqualTo("business failed");
    }

    @Test
    void shouldRestoreParentTraceContextAfterChildSpanFinished() {
        TraceContext parentContext = new TraceContext("trace-parent", "span-parent", "experiment-1");
        TraceContextHolder.set(parentContext);

        testTraceService.success();

        TraceSpan persistedSpan = capturePersistedSpan();
        assertThat(persistedSpan.getTraceId()).isEqualTo("trace-parent");
        assertThat(persistedSpan.getParentSpanId()).isEqualTo("span-parent");
        assertThat(persistedSpan.getExperimentId()).isEqualTo("experiment-1");
        assertThat(TraceContextHolder.get()).isSameAs(parentContext);
    }

    @Test
    void shouldClearTraceContextWhenRootSpanCreatedByAspect() {
        testTraceService.success();

        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void shouldNotSwallowBusinessException() {
        assertThatThrownBy(() -> testTraceService.fail())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("business failed");
    }

    private TraceSpan capturePersistedSpan() {
        ArgumentCaptor<TraceSpan> captor = ArgumentCaptor.forClass(TraceSpan.class);
        verify(traceSpanMapper).insert(captor.capture());
        return captor.getValue();
    }

    @TestConfiguration
    static class TraceAspectTestConfig {

        @Bean
        TestTraceService testTraceService() {
            return new TestTraceService();
        }
    }

    static class TestTraceService {

        @com.faultlab.backend.trace.annotation.TraceSpan(
                operationName = "successOperation",
                component = "trace-test",
                tags = "{\"case\":\"success\"}"
        )
        String success() {
            return "ok";
        }

        @com.faultlab.backend.trace.annotation.TraceSpan(operationName = "errorOperation", component = "trace-test")
        void fail() {
            throw new IllegalStateException("business failed");
        }
    }
}
