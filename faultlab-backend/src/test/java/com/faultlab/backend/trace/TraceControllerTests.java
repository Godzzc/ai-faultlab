package com.faultlab.backend.trace;

import com.faultlab.backend.trace.dto.TraceTreeResponse;
import com.faultlab.backend.trace.service.TraceQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(com.faultlab.backend.trace.controller.TraceController.class)
class TraceControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TraceQueryService traceQueryService;

    @Test
    void shouldReturnTraceTreeWithApiResponse() throws Exception {
        when(traceQueryService.getTraceTree("trace-1"))
                .thenReturn(new TraceTreeResponse("trace-1", List.of()));

        mockMvc.perform(get("/api/traces/trace-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.traceId").value("trace-1"))
                .andExpect(jsonPath("$.data.roots").isArray());
    }
}
