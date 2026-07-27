package com.faultlab.backend;

import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.runbook.mapper.RunbookDocMapper;
import com.faultlab.backend.task.mapper.LongSubTaskMapper;
import com.faultlab.backend.task.mapper.LongTaskMapper;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FaultLabBackendApplicationTests {

    @Autowired
    private FaultExperimentMapper faultExperimentMapper;

    @Autowired
    private TraceSpanMapper traceSpanMapper;

    @Autowired
    private FaultMetricMapper faultMetricMapper;

    @Autowired
    private DiagnosisReportMapper diagnosisReportMapper;

    @Autowired
    private RunbookDocMapper runbookDocMapper;

    @Autowired
    private LongTaskMapper longTaskMapper;

    @Autowired
    private LongSubTaskMapper longSubTaskMapper;

    @Test
    void contextLoads() {
        assertThat(faultExperimentMapper).isNotNull();
        assertThat(traceSpanMapper).isNotNull();
        assertThat(faultMetricMapper).isNotNull();
        assertThat(diagnosisReportMapper).isNotNull();
        assertThat(runbookDocMapper).isNotNull();
        assertThat(longTaskMapper).isNotNull();
        assertThat(longSubTaskMapper).isNotNull();
    }
}
