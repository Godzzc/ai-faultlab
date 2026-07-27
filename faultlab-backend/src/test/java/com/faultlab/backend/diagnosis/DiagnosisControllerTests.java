package com.faultlab.backend.diagnosis;

import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.rule.service.RuleDiagnosisService;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(com.faultlab.backend.diagnosis.controller.DiagnosisController.class)
class DiagnosisControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RuleDiagnosisService ruleDiagnosisService;

    @Test
    void shouldReturnRuleDiagnosisWithApiResponse() throws Exception {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId("exp-1");
        result.setFaultType(ScenarioCode.MQ_BACKLOG);
        result.setFaultName("MQ 消息堆积");
        result.setConfidence(0.85);
        result.setMatched(true);
        result.setReason("生产消息数大于消费消息数，且消费耗时较高，疑似 MQ 消息堆积。");
        result.setEvidence(List.of("publishCount=100", "consumeCount=20"));
        result.setSuggestions(List.of("增加消费者并发"));
        when(ruleDiagnosisService.diagnose("exp-1")).thenReturn(result);

        mockMvc.perform(post("/api/diagnosis/exp-1/rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.experimentId").value("exp-1"))
                .andExpect(jsonPath("$.data.faultType").value(ScenarioCode.MQ_BACKLOG))
                .andExpect(jsonPath("$.data.matched").value(true));
    }
}
