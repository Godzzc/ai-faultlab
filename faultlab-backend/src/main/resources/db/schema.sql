CREATE TABLE IF NOT EXISTS fault_experiment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    experiment_id VARCHAR(64) NOT NULL,
    scenario_code VARCHAR(64) NOT NULL,
    scenario_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    trace_id VARCHAR(64) DEFAULT NULL,
    start_time DATETIME DEFAULT NULL,
    end_time DATETIME DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fault_experiment_experiment_id (experiment_id),
    KEY idx_fault_experiment_trace_id (trace_id),
    KEY idx_fault_experiment_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trace_span (
    id BIGINT NOT NULL AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    span_id VARCHAR(64) NOT NULL,
    parent_span_id VARCHAR(64) DEFAULT NULL,
    experiment_id VARCHAR(64) DEFAULT NULL,
    operation_name VARCHAR(128) NOT NULL,
    component VARCHAR(64) NOT NULL,
    start_time DATETIME DEFAULT NULL,
    end_time DATETIME DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT DEFAULT NULL,
    tags_json TEXT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace_span_trace_span (trace_id, span_id),
    KEY idx_trace_span_experiment_id (experiment_id),
    KEY idx_trace_span_parent_span_id (parent_span_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fault_metric (
    id BIGINT NOT NULL AUTO_INCREMENT,
    experiment_id VARCHAR(64) NOT NULL,
    metric_name VARCHAR(128) NOT NULL,
    metric_value DECIMAL(20, 6) NOT NULL,
    metric_unit VARCHAR(32) DEFAULT NULL,
    component VARCHAR(64) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fault_metric_experiment_id (experiment_id),
    KEY idx_fault_metric_metric_name (metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS diagnosis_report (
    id BIGINT NOT NULL AUTO_INCREMENT,
    experiment_id VARCHAR(64) NOT NULL,
    fault_type VARCHAR(64) NOT NULL,
    fault_name VARCHAR(128) NOT NULL,
    confidence DECIMAL(5, 4) DEFAULT NULL,
    rule_result_json TEXT DEFAULT NULL,
    rag_docs_json TEXT DEFAULT NULL,
    ai_report_json TEXT DEFAULT NULL,
    fallback_report TEXT DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_diagnosis_report_experiment_id (experiment_id),
    KEY idx_diagnosis_report_fault_type (fault_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS runbook_doc (
    id BIGINT NOT NULL AUTO_INCREMENT,
    doc_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    fault_type VARCHAR(64) NOT NULL,
    component VARCHAR(64) DEFAULT NULL,
    tags VARCHAR(512) DEFAULT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_runbook_doc_doc_id (doc_id),
    KEY idx_runbook_doc_fault_type (fault_type),
    KEY idx_runbook_doc_component (component)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS long_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    experiment_id VARCHAR(64) DEFAULT NULL,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    running_count INT NOT NULL DEFAULT 0,
    progress DECIMAL(5, 2) NOT NULL DEFAULT 0.00,
    start_time DATETIME DEFAULT NULL,
    end_time DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_long_task_task_id (task_id),
    KEY idx_long_task_experiment_id (experiment_id),
    KEY idx_long_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS long_sub_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    sub_task_id VARCHAR(64) NOT NULL,
    shard_key VARCHAR(128) DEFAULT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry INT NOT NULL DEFAULT 0,
    input_json TEXT DEFAULT NULL,
    output_json TEXT DEFAULT NULL,
    error_message TEXT DEFAULT NULL,
    start_time DATETIME DEFAULT NULL,
    end_time DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_long_sub_task_sub_task_id (sub_task_id),
    KEY idx_long_sub_task_task_id (task_id),
    KEY idx_long_sub_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
