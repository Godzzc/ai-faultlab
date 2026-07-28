package com.faultlab.backend.rule.diagnoser;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ThreadPoolSaturationRuleDiagnoser implements RuleDiagnoser {

    private static final String FAULT_NAME = "线程池饱和";
    private static final int DEFAULT_MAX_POOL_SIZE = 2;
    private static final List<String> DEFAULT_SUGGESTIONS = List.of(
            "调整核心线程数和最大线程数",
            "合理设置任务队列容量",
            "对长耗时任务做异步拆分",
            "增加限流或削峰策略",
            "检查是否存在慢 SQL、远程调用超时或阻塞 IO",
            "增加拒绝策略监控和告警"
    );

    @Override
    public boolean supports(String scenarioCode) {
        return ScenarioCode.THREAD_POOL_SATURATION.equals(scenarioCode);
    }

    @Override
    public RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics) {
        Map<String, BigDecimal> metricValues = metrics == null ? Map.of() : metrics.stream()
                .filter(metric -> metric.getMetricName() != null && metric.getMetricValue() != null)
                .collect(Collectors.toMap(
                        FaultMetric::getMetricName,
                        FaultMetric::getMetricValue,
                        (left, right) -> right
                ));

        Optional<BigDecimal> taskCount = metric(metricValues, "taskCount");
        Optional<BigDecimal> acceptedTaskCount = metric(metricValues, "acceptedTaskCount");
        Optional<BigDecimal> rejectedTaskCount = metric(metricValues, "rejectedTaskCount");
        Optional<BigDecimal> activeThreadCount = metric(metricValues, "activeThreadCount");
        Optional<BigDecimal> queueSize = metric(metricValues, "queueSize");
        Optional<BigDecimal> avgTaskDurationMs = metric(metricValues, "avgTaskDurationMs");
        Optional<BigDecimal> taskSleepMs = metric(metricValues, "taskSleepMs");

        RuleDiagnosisResult result = baseResult(experiment);
        List<String> evidence = buildEvidence(
                taskCount,
                acceptedTaskCount,
                rejectedTaskCount,
                activeThreadCount,
                queueSize,
                avgTaskDurationMs,
                taskSleepMs
        );

        if (taskCount.isEmpty() || rejectedTaskCount.isEmpty() || activeThreadCount.isEmpty()
                || queueSize.isEmpty() || (avgTaskDurationMs.isEmpty() && taskSleepMs.isEmpty())) {
            result.setMatched(false);
            result.setConfidence(0.20);
            result.setReason("关键线程池指标不足，无法确认是否存在线程池饱和。");
            result.setEvidence(evidence);
            return result;
        }

        boolean rejected = rejectedTaskCount.get().longValue() > 0;
        boolean queued = queueSize.get().longValue() > 0;
        boolean activeFull = activeThreadCount.get().longValue() >= DEFAULT_MAX_POOL_SIZE;
        boolean longTask = avgTaskDurationMs.map(value -> value.compareTo(BigDecimal.valueOf(1000)) >= 0).orElse(false)
                || taskSleepMs.map(value -> value.compareTo(BigDecimal.valueOf(1000)) >= 0).orElse(false);

        if (rejected && queued) {
            result.setMatched(true);
            result.setConfidence(0.90);
            result.setReason("线程池出现拒绝任务且队列存在堆积，线程池疑似已饱和。");
        } else if (queued && activeFull) {
            result.setMatched(true);
            result.setConfidence(0.80);
            result.setReason("任务队列存在堆积，且工作线程已被占满，疑似线程池饱和。");
        } else if (longTask) {
            result.setMatched(true);
            result.setConfidence(0.60);
            result.setReason("任务执行耗时较长，可能导致线程池长任务阻塞。");
        } else {
            result.setMatched(false);
            result.setConfidence(0.20);
            result.setReason("暂未观察到拒绝任务、队列堆积或长任务阻塞证据。");
        }

        result.setEvidence(evidence);
        return result;
    }

    private RuleDiagnosisResult baseResult(FaultExperiment experiment) {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId(experiment == null ? null : experiment.getExperimentId());
        result.setFaultType(ScenarioCode.THREAD_POOL_SATURATION);
        result.setFaultName(FAULT_NAME);
        result.setSuggestions(DEFAULT_SUGGESTIONS);
        result.setEvidence(List.of());
        return result;
    }

    private List<String> buildEvidence(
            Optional<BigDecimal> taskCount,
            Optional<BigDecimal> acceptedTaskCount,
            Optional<BigDecimal> rejectedTaskCount,
            Optional<BigDecimal> activeThreadCount,
            Optional<BigDecimal> queueSize,
            Optional<BigDecimal> avgTaskDurationMs,
            Optional<BigDecimal> taskSleepMs
    ) {
        List<String> evidence = new ArrayList<>();
        taskCount.ifPresent(value -> evidence.add("taskCount=" + value.longValue()));
        acceptedTaskCount.ifPresent(value -> evidence.add("acceptedTaskCount=" + value.longValue()));
        rejectedTaskCount.ifPresent(value -> evidence.add("rejectedTaskCount=" + value.longValue()));
        activeThreadCount.ifPresent(value -> evidence.add("activeThreadCount=" + value.longValue()));
        queueSize.ifPresent(value -> evidence.add("queueSize=" + value.longValue()));
        avgTaskDurationMs.ifPresent(value -> evidence.add("avgTaskDurationMs=" + value.longValue()));
        taskSleepMs.ifPresent(value -> evidence.add("taskSleepMs=" + value.longValue()));
        return evidence;
    }

    private Optional<BigDecimal> metric(Map<String, BigDecimal> metricValues, String metricName) {
        return Optional.ofNullable(metricValues.get(metricName));
    }
}
