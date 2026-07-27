package com.faultlab.backend.trace.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.faultlab.backend.trace.dto.TraceSpanNode;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TraceQueryService {

    private final TraceSpanMapper traceSpanMapper;

    public TraceQueryService(TraceSpanMapper traceSpanMapper) {
        this.traceSpanMapper = traceSpanMapper;
    }

    public TraceTreeResponse getTraceTree(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return new TraceTreeResponse(traceId, List.of());
        }

        List<TraceSpan> traceSpans = traceSpanMapper.selectList(new LambdaQueryWrapper<TraceSpan>()
                .eq(TraceSpan::getTraceId, traceId)
                .orderByAsc(TraceSpan::getStartTime)
                .orderByAsc(TraceSpan::getCreatedAt));

        return new TraceTreeResponse(traceId, buildTraceTree(traceSpans));
    }

    public List<TraceSpanNode> buildTraceTree(List<TraceSpan> traceSpans) {
        if (traceSpans == null || traceSpans.isEmpty()) {
            return List.of();
        }

        List<TraceSpan> sortedSpans = traceSpans.stream()
                .sorted(Comparator.comparing(TraceSpan::getStartTime, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(TraceSpan::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(TraceSpan::getSpanId, Comparator.nullsLast(String::compareTo)))
                .toList();

        Map<String, TraceSpanNode> nodeBySpanId = new HashMap<>();
        for (TraceSpan traceSpan : sortedSpans) {
            if (StringUtils.hasText(traceSpan.getSpanId())) {
                nodeBySpanId.put(traceSpan.getSpanId(), toNode(traceSpan));
            }
        }

        List<TraceSpanNode> roots = new ArrayList<>();
        for (TraceSpan traceSpan : sortedSpans) {
            TraceSpanNode currentNode = nodeBySpanId.get(traceSpan.getSpanId());
            if (currentNode == null) {
                continue;
            }

            String parentSpanId = traceSpan.getParentSpanId();
            TraceSpanNode parentNode = StringUtils.hasText(parentSpanId) ? nodeBySpanId.get(parentSpanId) : null;
            if (parentNode == null) {
                roots.add(currentNode);
            } else {
                parentNode.getChildren().add(currentNode);
            }
        }

        sortChildrenRecursively(roots);
        return roots;
    }

    private void sortChildrenRecursively(List<TraceSpanNode> nodes) {
        nodes.sort(Comparator.comparing(TraceSpanNode::getStartTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(TraceSpanNode::getSpanId, Comparator.nullsLast(String::compareTo)));
        for (TraceSpanNode node : nodes) {
            sortChildrenRecursively(node.getChildren());
        }
    }

    private TraceSpanNode toNode(TraceSpan traceSpan) {
        TraceSpanNode node = new TraceSpanNode();
        node.setTraceId(traceSpan.getTraceId());
        node.setSpanId(traceSpan.getSpanId());
        node.setParentSpanId(traceSpan.getParentSpanId());
        node.setExperimentId(traceSpan.getExperimentId());
        node.setOperationName(traceSpan.getOperationName());
        node.setComponent(traceSpan.getComponent());
        node.setStartTime(traceSpan.getStartTime());
        node.setDurationMs(traceSpan.getDurationMs());
        node.setStatus(traceSpan.getStatus());
        node.setErrorMessage(traceSpan.getErrorMessage());
        node.setTagsJson(traceSpan.getTagsJson());
        return node;
    }
}
