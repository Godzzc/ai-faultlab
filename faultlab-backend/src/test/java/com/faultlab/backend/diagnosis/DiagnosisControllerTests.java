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

    @Test
    void shouldReturnThreadPoolSaturationRuleDiagnosisWithApiResponse() throws Exception {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId("exp-threadpool");
        result.setFaultType(ScenarioCode.THREAD_POOL_SATURATION);
        result.setFaultName("线程池饱和");
        result.setConfidence(0.90);
        result.setMatched(true);
        result.setReason("线程池出现拒绝任务且队列存在堆积，线程池疑似已饱和。");
        result.setEvidence(List.of("rejectedTaskCount=18", "queueSize=10"));
        result.setSuggestions(List.of("合理设置任务队列容量"));
        when(ruleDiagnosisService.diagnose("exp-threadpool")).thenReturn(result);

        mockMvc.perform(post("/api/diagnosis/exp-threadpool/rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.experimentId").value("exp-threadpool"))
                .andExpect(jsonPath("$.data.faultType").value(ScenarioCode.THREAD_POOL_SATURATION))
                .andExpect(jsonPath("$.data.confidence").value(0.90))
                .andExpect(jsonPath("$.data.matched").value(true));
    }

    @Test
    void shouldReturnIdempotencyConflictRuleDiagnosisWithApiResponse() throws Exception {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId("exp-idempotency");
        result.setFaultType(ScenarioCode.IDEMPOTENCY_CONFLICT);
        result.setFaultName("幂等冲突");
        result.setConfidence(0.90);
        result.setMatched(true);
        result.setReason("同一个幂等 key 同时出现不同请求体和重复提交，疑似幂等冲突。");
        result.setEvidence(List.of("duplicateCount=20", "hashMismatchCount=8"));
        result.setSuggestions(List.of("服务端保存 requestHash，避免同一幂等 key 携带不同请求体"));
        when(ruleDiagnosisService.diagnose("exp-idempotency")).thenReturn(result);

        mockMvc.perform(post("/api/diagnosis/exp-idempotency/rule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.experimentId").value("exp-idempotency"))
                .andExpect(jsonPath("$.data.faultType").value(ScenarioCode.IDEMPOTENCY_CONFLICT))
                .andExpect(jsonPath("$.data.confidence").value(0.90))
                .andExpect(jsonPath("$.data.matched").value(true));
    }
}
