package com.faultlab.backend.trace.dto;

import java.util.List;

public class TraceTreeResponse {

    private String traceId;
    private List<TraceSpanNode> roots;

    public TraceTreeResponse(String traceId, List<TraceSpanNode> roots) {
        this.traceId = traceId;
        this.roots = roots;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public List<TraceSpanNode> getRoots() {
        return roots;
    }

    public void setRoots(List<TraceSpanNode> roots) {
        this.roots = roots;
    }
}
