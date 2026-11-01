-- ============================================================================
-- 示例模板：Excel循环渲染（template_code = ExcelDataRows，file_type = XLSX，export_type = PDF）
-- 模板文件：docs/public/demo/excel-render-test.xlsx（库内文件名仍是 打印测试.xlsx，内容一致）
--
-- 一个数据块：
--   user  SQL  —— 不查任何表，直接 union all 三行假数据，顺便带一列头像地址当图片字段
--
-- 模板结构（Sheet1）：
--   A1 用户名称   B1 用户账号   C1 手机号   D1 头像
--   A2 {{#each user}}${nick_name}   B2 ${user_name}   C2 ${phone_number}   D2 ${pic}
--   Sheet2 / Sheet3 是空表，导出 PDF 时会被自动跳过
--
-- 前置条件：
--   * MySQL 8 / MariaDB，已建好 doc_template / doc_template_data_block；
--   * 不依赖任何业务表，执行完就能生成文档；
--   * 图片字段会真的去下载 pic 指向的地址（5 秒超时、单张 5MB），离线环境请换成可访问的图。
-- 可重复执行：唯一键 (template_id, block_code)，重跑会覆盖成同一份配置。
-- ============================================================================

-- 1) 模板元数据。已存在时不动名称
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('ExcelDataRows', 'Excel循环渲染', 'XLSX', 'PDF', 'ENABLED', '示例模板：整行循环 + 图片字段')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'ExcelDataRows' ORDER BY id LIMIT 1);

SELECT IF(@template_id IS NULL, '模板元数据插入失败，请检查 doc_template 表', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- 2) 数据块
--    single=false：返回数组，配合模板里的 {{#each user}} 做整行循环；
--    maxRows=1000：防止误查全表；
--    dataSource=primary：用宿主主库（留空也是这个效果）；
--    imageFields：把 pic 当图片渲染，50×50 等比缩放，必须独占单元格。
--    注意 SQL 里那对 '' 是转义后的单引号，最终存进 JSON 的是 '张三' 这样的字面量。
INSERT INTO doc_template_data_block
    (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
    (@template_id, 'user', '用户列表', 'SQL',
     '{"sql":"select ''张三'' as nick_name, ''zhangsan'' as user_name, ''13800000001'' as phone_number, ''https://07akioni.oss-cn-beijing.aliyuncs.com/07akioni.jpeg'' as pic union all select ''李四'' as nick_name, ''lisi'' as user_name, ''13800000002'' as phone_number, ''https://07akioni.oss-cn-beijing.aliyuncs.com/07akioni.jpeg'' as pic union all select ''王五'' as nick_name, ''wangwu'' as user_name, ''13800000003'' as phone_number, ''https://07akioni.oss-cn-beijing.aliyuncs.com/07akioni.jpeg'' as pic","single":false,"maxRows":1000,"dataSource":"primary","imageFields":[{"field":"pic","width":50,"height":50}]}',
     '{}', 1, 'ENABLED')
ON DUPLICATE KEY UPDATE
    block_name         = VALUES(block_name),
    data_source_type   = VALUES(data_source_type),
    data_source_config = VALUES(data_source_config),
    param_config       = VALUES(param_config),
    sort_order         = VALUES(sort_order),
    status             = VALUES(status);

-- 3) 核对
SELECT id, template_id, block_code, block_name, data_source_type, sort_order, status
FROM doc_template_data_block
WHERE template_id = @template_id
ORDER BY sort_order, id;
