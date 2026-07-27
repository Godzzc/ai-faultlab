package com.faultlab.backend.trace.controller;

import com.faultlab.backend.common.ApiResponse;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import com.faultlab.backend.trace.service.TraceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceQueryService traceQueryService;

    public TraceController(TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }

    @GetMapping("/{traceId}")
    public ApiResponse<TraceTreeResponse> getTraceTree(@PathVariable String traceId) {
        return ApiResponse.success(traceQueryService.getTraceTree(traceId));
    }
}
