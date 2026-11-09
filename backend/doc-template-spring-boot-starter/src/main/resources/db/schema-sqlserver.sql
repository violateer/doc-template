-- SQL Server 2016+ 建表脚本。交给宿主的数据库迁移流程执行，或让 Starter 启动时自动建表。
-- 与 db/schema.sql（MySQL / MariaDB）、db/schema-postgresql.sql 保持列一致：加字段时三份都要改，测试会比对列名集合。
-- 与另外两份的两处差异：
--   1. TIMESTAMP 在 SQL Server 里是二进制行版本（ROWVERSION），时间列一律 DATETIME2；
--   2. 文本列用 NVARCHAR 而不是 VARCHAR，避免库的默认排序规则把中文写成问号。
-- CREATE TABLE 没有 IF NOT EXISTS，用 OBJECT_ID 判断表在不在，脚本可以重复执行。
IF OBJECT_ID(N'doc_template', N'U') IS NULL
CREATE TABLE doc_template (
 id BIGINT IDENTITY(1,1) PRIMARY KEY, template_code NVARCHAR(100) NOT NULL UNIQUE, template_name NVARCHAR(200) NOT NULL,
 file_type NVARCHAR(20) NOT NULL, export_type NVARCHAR(20) NOT NULL DEFAULT 'ORIGINAL', current_version_id BIGINT NULL,
 status NVARCHAR(20) NOT NULL DEFAULT 'ENABLED', remark NVARCHAR(500),
 create_time DATETIME2 DEFAULT CURRENT_TIMESTAMP, update_time DATETIME2 DEFAULT CURRENT_TIMESTAMP
);
IF OBJECT_ID(N'doc_template_version', N'U') IS NULL
CREATE TABLE doc_template_version (
 id BIGINT IDENTITY(1,1) PRIMARY KEY, template_id BIGINT NOT NULL, version_no INT NOT NULL, file_name NVARCHAR(255) NOT NULL,
 file_url NVARCHAR(1000) NOT NULL, file_sha256 NVARCHAR(64), create_time DATETIME2 DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_version_no UNIQUE (template_id, version_no)
);
IF OBJECT_ID(N'doc_template_data_block', N'U') IS NULL
CREATE TABLE doc_template_data_block (
 id BIGINT IDENTITY(1,1) PRIMARY KEY, template_id BIGINT NOT NULL, block_code NVARCHAR(100) NOT NULL, block_name NVARCHAR(200) NOT NULL,
 data_source_type NVARCHAR(30) NOT NULL, data_source_config NVARCHAR(MAX) NOT NULL, param_config NVARCHAR(MAX),
 sort_order INT NOT NULL DEFAULT 0, status NVARCHAR(20) NOT NULL DEFAULT 'ENABLED',
 CONSTRAINT uk_data_block_code UNIQUE (template_id, block_code)
);
IF OBJECT_ID(N'doc_template_generate_record', N'U') IS NULL
CREATE TABLE doc_template_generate_record (
 id BIGINT IDENTITY(1,1) PRIMARY KEY, template_id BIGINT NOT NULL, template_version_id BIGINT NULL, export_type NVARCHAR(20) NOT NULL,
 request_params NVARCHAR(MAX), result_file_url NVARCHAR(1000), status NVARCHAR(20) NOT NULL, error_message NVARCHAR(MAX),
 create_by NVARCHAR(100), create_time DATETIME2 DEFAULT CURRENT_TIMESTAMP
);
-- 数据字典：主表一本字典一行（编码 + 名称），子表一行一个「值 → 显示名」。
-- 字典按 dict_code 被转换器引用，dict_code 全局唯一。
IF OBJECT_ID(N'doc_template_dict', N'U') IS NULL
CREATE TABLE doc_template_dict (
 id BIGINT IDENTITY(1,1) PRIMARY KEY, dict_code NVARCHAR(100) NOT NULL UNIQUE, dict_name NVARCHAR(200) NOT NULL,
 remark NVARCHAR(500) NULL, create_time DATETIME2 DEFAULT CURRENT_TIMESTAMP, update_time DATETIME2 DEFAULT CURRENT_TIMESTAMP
);
IF OBJECT_ID(N'doc_template_dict_item', N'U') IS NULL
CREATE TABLE doc_template_dict_item (
 id BIGINT IDENTITY(1,1) PRIMARY KEY, dict_id BIGINT NOT NULL, item_value NVARCHAR(200) NOT NULL,
 item_label NVARCHAR(500) NOT NULL, sort_order INT NOT NULL DEFAULT 0, create_time DATETIME2 DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_dict_item_value UNIQUE (dict_id, item_value)
);
-- 普通索引在 SQL Server 里也要独立建（MySQL 那份写在 CREATE TABLE 里）
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_dict_item_dict'
               AND object_id = OBJECT_ID(N'doc_template_dict_item'))
CREATE INDEX idx_dict_item_dict ON doc_template_dict_item (dict_id);
