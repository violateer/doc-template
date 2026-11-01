-- ============================================================================
-- 示例模板：嵌套循环（Excel，template_code = ExcelNestedLoop，file_type = XLSX）
-- 模板文件：docs/public/demo/excel-nested-loop.xlsx
--
-- 三个工作表各演示一种写法（与 Word 示例同一套数据）：
--   「嵌套字段」表   区域循环 + 嵌套字段（orders.items 自带明细）
--   「关联过滤」表   区域循环 + where 关联过滤（orders 与 order_items 都扁平）
--   「行内单行循环」表 标记写在单元格里，整行按数组重复
--
-- 3 个数据块（全部 SQL）：
--   report       single=true   标题 / 合计
--   orders       single=false  3 个订单，items 列用 json_array 拼成嵌套数组
--   order_items  single=false  6 行扁平明细
--
-- Excel 侧注意：循环区域里每行的「标记行」会被整行删掉，所以标记必须自己占一行；
--   行内标记（写法 3）则写在数据行的任意单元格里。
--
-- 前置条件：MySQL 8 / MariaDB；doc_template / doc_template_data_block 由 Starter 自动建表。
-- 本脚本只写模板元数据与数据块配置，可重复执行（唯一键覆盖）。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('ExcelNestedLoop', '嵌套循环示例（Excel）', 'XLSX', 'ORIGINAL', 'ENABLED',
        '示例模板：三个工作表分别演示嵌套字段 / where 关联过滤 / 行内单行循环')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'ExcelNestedLoop' ORDER BY id LIMIT 1);
SELECT IF(@template_id IS NULL, '模板元数据插入失败', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- ----------------------------------------------------------------- 2. 数据块
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  (@template_id, 'report', '报告主信息', 'SQL',
   '{"sql":"select ''嵌套循环示例（Excel）'' as title, ''27084.00'' as total","single":true,"maxRows":1}',
   '{}', 10, 'ENABLED'),

  (@template_id, 'orders', '订单（含嵌套明细）', 'SQL',
   '{"sql":"select ''SO-2026-0001'' as order_no, ''杭州云栖智造有限公司'' as customer, ''15180.00'' as amount, cast(json_array(json_object(''sku'',''不锈钢法兰 DN100'',''qty'',120,''price'',''86.50''), json_object(''sku'',''石墨缠绕垫片 DN100'',''qty'',240,''price'',''12.80''), json_object(''sku'',''六角螺栓套装 M20×90'',''qty'',480,''price'',''3.60'')) as char) as items union all select ''SO-2026-0002'', ''宁波海天精工股份有限公司'', ''10224.00'', cast(json_array(json_object(''sku'',''球阀 Q41F-16P DN50'',''qty'',60,''price'',''158.00''), json_object(''sku'',''法兰垫片 DN50'',''qty'',120,''price'',''6.20'')) as char) union all select ''SO-2026-0003'', ''苏州汇川技术有限公司'', ''1680.00'', cast(json_array(json_object(''sku'',''压力表 Y-100'',''qty'',40,''price'',''42.00'')) as char)","single":false,"maxRows":100}',
   '{}', 20, 'ENABLED'),

  (@template_id, 'order_items', '订单明细（扁平）', 'SQL',
   '{"sql":"select ''SO-2026-0001'' as order_no, ''不锈钢法兰 DN100'' as sku, 120 as qty, ''86.50'' as price, ''10380.00'' as amount union all select ''SO-2026-0001'', ''石墨缠绕垫片 DN100'', 240, ''12.80'', ''3072.00'' union all select ''SO-2026-0001'', ''六角螺栓套装 M20×90'', 480, ''3.60'', ''1728.00'' union all select ''SO-2026-0002'', ''球阀 Q41F-16P DN50'', 60, ''158.00'', ''9480.00'' union all select ''SO-2026-0002'', ''法兰垫片 DN50'', 120, ''6.20'', ''744.00'' union all select ''SO-2026-0003'', ''压力表 Y-100'', 40, ''42.00'', ''1680.00''","single":false,"maxRows":500}',
   '{}', 30, 'ENABLED')
ON DUPLICATE KEY UPDATE
  block_name         = VALUES(block_name),
  data_source_type   = VALUES(data_source_type),
  data_source_config = VALUES(data_source_config),
  param_config       = VALUES(param_config),
  sort_order         = VALUES(sort_order),
  status             = VALUES(status);

-- ----------------------------------------------------------------- 3. 核对
SELECT block_code, block_name, sort_order, status
FROM doc_template_data_block WHERE template_id = @template_id ORDER BY sort_order, id;
