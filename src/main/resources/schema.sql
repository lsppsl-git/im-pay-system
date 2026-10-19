USE chat_system;

ALTER TABLE users
  ADD COLUMN nickname VARCHAR(50) DEFAULT '',
  ADD COLUMN avatar VARCHAR(255),
  ADD COLUMN updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE users MODIFY COLUMN id BIGINT AUTO_INCREMENT;

ALTER TABLE messages
  ADD COLUMN msg_id VARCHAR(64),
  ADD COLUMN type VARCHAR(20) DEFAULT 'TEXT',
  ADD COLUMN from_user_id BIGINT,
  ADD COLUMN to_user_id BIGINT,
  ADD COLUMN status VARCHAR(20) DEFAULT 'SENT',
  ADD COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP;

CREATE TABLE IF NOT EXISTS file_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    upload_id VARCHAR(64) NOT NULL,
    file_name VARCHAR(255),
    file_type VARCHAR(100),
    file_size BIGINT,
    md5 VARCHAR(64),
    file_path VARCHAR(255),
    total_chunks INT DEFAULT 1,
    uploaded_chunks INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'UPLOADING',
    user_id BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_md5 (md5)
);

CREATE TABLE IF NOT EXISTS wallet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    balance DECIMAL(18,2) DEFAULT 0.00,
    version INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transaction_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    transaction_no VARCHAR(64) NOT NULL UNIQUE,
    from_user_id BIGINT,
    to_user_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',
    remark VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_from_user (from_user_id),
    INDEX idx_to_user (to_user_id)
);

CREATE TABLE IF NOT EXISTS red_packet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    packet_no VARCHAR(64) NOT NULL UNIQUE,
    sender_id BIGINT NOT NULL,
    total_amount DECIMAL(18,2) NOT NULL,
    total_count INT NOT NULL,
    remain_amount DECIMAL(18,2) NOT NULL,
    remain_count INT NOT NULL,
    message VARCHAR(255),
    type VARCHAR(10) DEFAULT 'RANDOM',
    status VARCHAR(20) DEFAULT 'ACTIVE',
    expired_at DATETIME NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS red_packet_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    red_packet_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_packet_user (red_packet_id, user_id),
    INDEX idx_packet_id (red_packet_id)
);

-- 2026-10-16 抢红包乐观锁：version 版本号
ALTER TABLE red_packet ADD COLUMN version INT NOT NULL DEFAULT 0;