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
public class DbFailureRuleDiagnoser implements RuleDiagnoser {

    private static final List<String> SLOW_QUERY_SUGGESTIONS = List.of(
            "添加索引",
            "避免函数或隐式转换导致索引失效",
            "优化分页",
            "减少返回字段",
            "使用覆盖索引",
            "分析执行计划",
            "读写分离"
    );
    private static final List<String> LOCK_CONTENTION_SUGGESTIONS = List.of(
            "缩短事务范围",
            "避免事务内远程调用",
            "降低锁粒度",
            "拆分热点行",
            "增加锁等待超时",
            "使用乐观锁",
            "异步化非核心写入"
    );
    private static final List<String> CONNECTION_POOL_SUGGESTIONS = List.of(
            "优化慢 SQL",
            "缩短事务",
            "合理配置连接池大小",
            "设置获取连接超时",
            "隔离读写流量",
            "增加降级兜底",
            "排查连接泄漏"
    );

    @Override
    public boolean supports(String scenarioCode) {
        return ScenarioCode.DB_SLOW_QUERY.equals(scenarioCode)
                || ScenarioCode.DB_LOCK_CONTENTION.equals(scenarioCode)
                || ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION.equals(scenarioCode);
    }

    @Override
    public RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics) {
        Map<String, BigDecimal> metricValues = toMetricValues(metrics);
        String scenarioCode = experiment == null ? null : experiment.getScenarioCode();
        if (ScenarioCode.DB_SLOW_QUERY.equals(scenarioCode)) {
            return diagnoseSlowQuery(experiment, metricValues);
        }
        if (ScenarioCode.DB_LOCK_CONTENTION.equals(scenarioCode)) {
            return diagnoseLockContention(experiment, metricValues);
        }
        return diagnoseConnectionPool(experiment, metricValues);
    }

    private RuleDiagnosisResult diagnoseSlowQuery(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "db.query.count");
        Optional<BigDecimal> slowQueryCount = metric(metrics, "db.slow.query.count");
        Optional<BigDecimal> slowQueryRate = metric(metrics, "db.slow.query.rate");
        Optional<BigDecimal> avgQueryMs = metric(metrics, "db.avg.query.ms");
        Optional<BigDecimal> maxQueryMs = metric(metrics, "db.max.query.ms");
        Optional<BigDecimal> fullScanCount = metric(metrics, "db.full.scan.count");
        Optional<BigDecimal> scannedRows = metric(metrics, "db.scanned.rows");
        Optional<BigDecimal> tableSize = metric(metrics, "db.table.size");

        RuleDiagnosisResult result = baseResult(experiment, ScenarioCode.DB_SLOW_QUERY, "慢 SQL / 全表扫描", SLOW_QUERY_SUGGESTIONS);
        result.setEvidence(evidence(
                item("requestCount", requestCount),
                textItem("queryMode", fullScanCount.map(value -> value.compareTo(BigDecimal.ZERO) > 0 ? "FULL_SCAN" : "INDEXED")),
                item("slowQueryCount", slowQueryCount),
                item("avgQueryMs", avgQueryMs),
                item("maxQueryMs", maxQueryMs),
                item("fullScanCount", fullScanCount),
                item("scannedRows", scannedRows),
                item("tableSize", tableSize)
        ));

        boolean highSlowRate = greaterOrEqual(slowQueryRate, BigDecimal.valueOf(0.50D));
        boolean highAvgQuery = greaterOrEqual(avgQueryMs, BigDecimal.valueOf(50D));
        boolean manyFullScans = greaterThanZero(fullScanCount);
        boolean scannedRowsCloseToTable = scannedRows.isPresent() && tableSize
                .map(value -> scannedRows.get().compareTo(value.multiply(BigDecimal.valueOf(0.70D))) >= 0)
                .orElse(false);

        if (highSlowRate || highAvgQuery || manyFullScans || scannedRowsCloseToTable) {
            result.setMatched(true);
            result.setConfidence(manyFullScans && scannedRowsCloseToTable ? 0.90 : 0.78);
            result.setReason("查询未命中有效索引或扫描行数过多，导致 DB 查询耗时升高");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("查询扫描范围和耗时较低，暂未命中慢 SQL 高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult diagnoseLockContention(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "db.lock.request.count");
        Optional<BigDecimal> lockWaitCount = metric(metrics, "db.lock.wait.count");
        Optional<BigDecimal> avgLockWaitMs = metric(metrics, "db.avg.lock.wait.ms");
        Optional<BigDecimal> maxLockWaitMs = metric(metrics, "db.max.lock.wait.ms");
        Optional<BigDecimal> updateTimeoutCount = metric(metrics, "db.update.timeout.count");
        Optional<BigDecimal> longTransactionCount = metric(metrics, "db.long.transaction.count");
        Optional<BigDecimal> activeTransactionCount = metric(metrics, "db.transaction.active.count");

        RuleDiagnosisResult result = baseResult(
                experiment,
                ScenarioCode.DB_LOCK_CONTENTION,
                "数据库锁竞争 / 长事务阻塞",
                LOCK_CONTENTION_SUGGESTIONS
        );
        result.setEvidence(evidence(
                Optional.of("targetRowId=hot-row"),
                item("requestCount", requestCount),
                item("concurrency", activeTransactionCount),
                item("lockWaitCount", lockWaitCount),
                item("avgLockWaitMs", avgLockWaitMs),
                item("maxLockWaitMs", maxLockWaitMs),
                item("updateTimeoutCount", updateTimeoutCount),
                item("longTransactionCount", longTransactionCount)
        ));

        boolean manyLockWaits = greaterOrEqual(lockWaitCount, BigDecimal.valueOf(5));
        boolean highAvgWait = greaterOrEqual(avgLockWaitMs, BigDecimal.valueOf(50D));
        boolean hasTimeout = greaterThanZero(updateTimeoutCount);
        boolean hasLongTransaction = greaterThanZero(longTransactionCount);

        if (manyLockWaits || highAvgWait || hasTimeout || hasLongTransaction) {
            result.setMatched(true);
            result.setConfidence(hasTimeout || hasLongTransaction ? 0.90 : 0.78);
            result.setReason("长事务或热点行更新导致锁持有时间过长，其他请求等待同一行锁，接口延迟升高或超时");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("锁等待和超时指标较低，暂未命中数据库锁竞争高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult diagnoseConnectionPool(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "db.connection.acquire.count");
        Optional<BigDecimal> maxPoolSize = metric(metrics, "db.pool.max.size");
        Optional<BigDecimal> activeCount = metric(metrics, "db.pool.active.count");
        Optional<BigDecimal> acquireTimeoutCount = metric(metrics, "db.connection.acquire.timeout.count");
        Optional<BigDecimal> acquireAvgMs = metric(metrics, "db.connection.acquire.avg.ms");
        Optional<BigDecimal> holdAvgMs = metric(metrics, "db.connection.hold.avg.ms");
        Optional<BigDecimal> apiErrorCount = metric(metrics, "api.error.count");

        RuleDiagnosisResult result = baseResult(
                experiment,
                ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION,
                "数据库连接池耗尽",
                CONNECTION_POOL_SUGGESTIONS
        );
        result.setEvidence(evidence(
                item("requestCount", requestCount),
                item("maxPoolSize", maxPoolSize),
                item("activeCount", activeCount),
                item("acquireTimeoutCount", acquireTimeoutCount),
                item("acquireAvgMs", acquireAvgMs),
                item("connectionHoldAvgMs", holdAvgMs),
                item("apiErrorCount", apiErrorCount)
        ));

        boolean hasAcquireTimeout = greaterThanZero(acquireTimeoutCount);
        boolean poolNearMax = activeCount.isPresent() && maxPoolSize
                .map(value -> activeCount.get().compareTo(value.multiply(BigDecimal.valueOf(0.90D))) >= 0)
                .orElse(false);
        boolean highAcquireAvg = greaterOrEqual(acquireAvgMs, BigDecimal.valueOf(50D));
        boolean errorsRaised = greaterThanZero(apiErrorCount);

        if (hasAcquireTimeout || poolNearMax || highAcquireAvg || errorsRaised) {
            result.setMatched(true);
            result.setConfidence(hasAcquireTimeout || errorsRaised ? 0.90 : 0.80);
            result.setReason("慢查询或长事务占用连接过久，连接池 active 连接接近上限，新请求获取连接等待或超时");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("连接池 active、等待和超时指标较低，暂未命中连接池耗尽高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult baseResult(
            FaultExperiment experiment,
            String faultType,
            String faultName,
            List<String> suggestions
    ) {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId(experiment == null ? null : experiment.getExperimentId());
        result.setFaultType(faultType);
        result.setFaultName(faultName);
        result.setSuggestions(suggestions);
        result.setEvidence(List.of());
        return result;
    }

    private Map<String, BigDecimal> toMetricValues(List<FaultMetric> metrics) {
        return metrics == null ? Map.of() : metrics.stream()
                .filter(metric -> metric.getMetricName() != null && metric.getMetricValue() != null)
                .collect(Collectors.toMap(
                        FaultMetric::getMetricName,
                        FaultMetric::getMetricValue,
                        (left, right) -> right
                ));
    }

    @SafeVarargs
    private final List<String> evidence(Optional<String>... items) {
        List<String> evidence = new ArrayList<>();
        for (Optional<String> item : items) {
            item.ifPresent(evidence::add);
        }
        return evidence;
    }

    private Optional<String> item(String name, Optional<BigDecimal> value) {
        return value.map(number -> name + "=" + number.stripTrailingZeros().toPlainString());
    }

    private Optional<String> textItem(String name, Optional<String> value) {
        return value.map(text -> name + "=" + text);
    }

    private Optional<BigDecimal> metric(Map<String, BigDecimal> metricValues, String metricName) {
        return Optional.ofNullable(metricValues.get(metricName));
    }

    private boolean greaterThanZero(Optional<BigDecimal> value) {
        return value.map(number -> number.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }

    private boolean greaterOrEqual(Optional<BigDecimal> value, BigDecimal threshold) {
        return value.map(number -> number.compareTo(threshold) >= 0).orElse(false);
    }
}
