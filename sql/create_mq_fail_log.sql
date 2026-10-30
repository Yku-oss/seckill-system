DROP TABLE IF EXISTS mq_fail_log;
CREATE TABLE mq_fail_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    correlation_id VARCHAR(128) DEFAULT NULL,
    exchange_name VARCHAR(128) DEFAULT NULL,
    routing_key VARCHAR(255) DEFAULT NULL,
    message_body TEXT DEFAULT NULL,
    fail_type VARCHAR(32) NOT NULL,
    fail_reason VARCHAR(512) DEFAULT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_retry (status, retry_count),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ fail log';
