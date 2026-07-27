package com.faultlab.backend.trace;

import com.faultlab.backend.trace.dto.TraceSpanNode;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.service.TraceQueryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceQueryServiceTests {

    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceQueryService traceQueryService = new TraceQueryService(traceSpanMapper);

    @Test
    void shouldBuildSingleRootTraceTree() {
        TraceSpan root = span("trace-1", "root", null, "root", time(1));
        TraceSpan child = span("trace-1", "child", "root", "child", time(2));

        List<TraceSpanNode> roots = traceQueryService.buildTraceTree(List.of(root, child));

        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).getSpanId()).isEqualTo("root");
        assertThat(roots.get(0).getChildren()).hasSize(1);
        assertThat(roots.get(0).getChildren().get(0).getSpanId()).isEqualTo("child");
    }

    @Test
    void shouldBuildNestedTraceTree() {
        TraceSpan root = span("trace-1", "root", null, "root", time(1));
        TraceSpan child = span("trace-1", "child", "root", "child", time(2));
        TraceSpan grandChild = span("trace-1", "grand-child", "child", "grandChild", time(3));

        List<TraceSpanNode> roots = traceQueryService.buildTraceTree(List.of(grandChild, child, root));

        TraceSpanNode rootNode = roots.get(0);
        TraceSpanNode childNode = rootNode.getChildren().get(0);
        assertThat(rootNode.getSpanId()).isEqualTo("root");
        assertThat(childNode.getSpanId()).isEqualTo("child");
        assertThat(childNode.getChildren()).hasSize(1);
        assertThat(childNode.getChildren().get(0).getSpanId()).isEqualTo("grand-child");
    }

    @Test
    void shouldSortChildrenByStartTime() {
        TraceSpan root = span("trace-1", "root", null, "root", time(1));
        TraceSpan secondChild = span("trace-1", "second-child", "root", "secondChild", time(3));
        TraceSpan firstChild = span("trace-1", "first-child", "root", "firstChild", time(2));

        List<TraceSpanNode> roots = traceQueryService.buildTraceTree(List.of(root, secondChild, firstChild));

        assertThat(roots.get(0).getChildren())
                .extracting(TraceSpanNode::getSpanId)
                .containsExactly("first-child", "second-child");
    }

    @Test
    void shouldHandleEmptyTraceSpanList() {
        when(traceSpanMapper.selectList(any())).thenReturn(List.of());

        TraceTreeResponse response = traceQueryService.getTraceTree("missing-trace");

        assertThat(response.getTraceId()).isEqualTo("missing-trace");
        assertThat(response.getRoots()).isEmpty();
    }

    @Test
    void shouldHandleOrphanSpanWithoutThrowingException() {
        TraceSpan root = span("trace-1", "root", null, "root", time(1));
        TraceSpan orphan = span("trace-1", "orphan", "missing-parent", "orphan", time(2));

        List<TraceSpanNode> roots = traceQueryService.buildTraceTree(List.of(orphan, root));

        assertThat(roots).hasSize(2);
        assertThat(roots).extracting(TraceSpanNode::getSpanId).containsExactly("root", "orphan");
    }

    private TraceSpan span(String traceId, String spanId, String parentSpanId, String operationName, LocalDateTime startTime) {
        TraceSpan traceSpan = new TraceSpan();
        traceSpan.setTraceId(traceId);
        traceSpan.setSpanId(spanId);
        traceSpan.setParentSpanId(parentSpanId);
        traceSpan.setExperimentId("experiment-1");
        traceSpan.setOperationName(operationName);
        traceSpan.setComponent("test");
        traceSpan.setStartTime(startTime);
        traceSpan.setDurationMs(10L);
        traceSpan.setStatus("SUCCESS");
        traceSpan.setTagsJson("{}");
        traceSpan.setCreatedAt(startTime.plusSeconds(1));
        return traceSpan;
    }

    private LocalDateTime time(int second) {
        return LocalDateTime.of(2026, 7, 27, 10, 0, second);
    }
}
