-- MySQL 8+ 建表脚本。交给宿主的数据库迁移流程执行，或让 Starter 启动时自动建表。
CREATE TABLE IF NOT EXISTS doc_template (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, template_code VARCHAR(100) NOT NULL UNIQUE, template_name VARCHAR(200) NOT NULL,
 file_type VARCHAR(20) NOT NULL, export_type VARCHAR(20) NOT NULL DEFAULT 'ORIGINAL', current_version_id BIGINT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', remark VARCHAR(500), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS doc_template_version (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, template_id BIGINT NOT NULL, version_no INT NOT NULL, file_name VARCHAR(255) NOT NULL,
 file_url VARCHAR(1000) NOT NULL, file_sha256 VARCHAR(64), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(template_id, version_no)
);
CREATE TABLE IF NOT EXISTS doc_template_data_block (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, template_id BIGINT NOT NULL, block_code VARCHAR(100) NOT NULL, block_name VARCHAR(200) NOT NULL,
 data_source_type VARCHAR(30) NOT NULL, data_source_config TEXT NOT NULL, param_config TEXT, sort_order INT NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL DEFAULT 'ENABLED', UNIQUE(template_id, block_code)
);
CREATE TABLE IF NOT EXISTS doc_template_generate_record (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, template_id BIGINT NOT NULL, template_version_id BIGINT NULL, export_type VARCHAR(20) NOT NULL,
 request_params TEXT, result_file_url VARCHAR(1000), status VARCHAR(20) NOT NULL, error_message TEXT, create_by VARCHAR(100), create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
-- 数据字典：主表一本字典一行（编码 + 名称），子表一行一个「值 → 显示名」。
-- 字典按 dict_code 被转换器引用，dict_code 全局唯一。
-- 注意：0.1 早期的 doc_template_dict 是扁平结构（一行一个映射），升级时需要先 DROP 掉旧表再启动。
CREATE TABLE IF NOT EXISTS doc_template_dict (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, dict_code VARCHAR(100) NOT NULL UNIQUE, dict_name VARCHAR(200) NOT NULL,
 remark VARCHAR(500) NULL, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS doc_template_dict_item (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, dict_id BIGINT NOT NULL, item_value VARCHAR(200) NOT NULL,
 item_label VARCHAR(500) NOT NULL, sort_order INT NOT NULL DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dict_item_value (dict_id, item_value), KEY idx_dict_item_dict (dict_id)
);
