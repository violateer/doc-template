-- ============================================================================
-- 示例模板：竖向合并单元格（Excel，template_code = ExcelMergeCells，file_type = XLSX）
-- 模板文件：docs/public/demo/excel-merge-cells.xlsx
--
-- 三个工作表分别演示三种合并写法（与 Word 示例同一套数据）：
--   「按主键合并」表  merge=A,B by=material_code   列字母写法，按物料主键合并两列
--   「按文本合并」表  merge=1                      列号写法，不写 by；空值不合并
--   「嵌套合并」表    merge=1 by=order_no           外层列跨内层展开的多行合并
--
-- 4 个数据块（全部 SQL）：
--   report        single=true   标题 / 入库合计
--   stock_items   single=false  7 行入库明细（同一物料多批次、含两行空仓库）
--   orders        single=false  3 个订单
--   order_items   single=false  6 行扁平明细（嵌套那张表用 where 关联过滤）
--
-- Excel 侧注意：
--   1. 区域循环的标记行会被整行删掉，所以 merge 指向标记所在列（A 列）也不会误伤数据；
--   2. 列字母与 1 起始列号等价：merge=A,B 与 merge=1,2 是同一个意思；
--   3. 模板里预先画好的跨行合并区在循环块内会被移除，竖向合并一律用 merge 声明。
--
-- 前置条件：MySQL 8 / MariaDB；doc_template / doc_template_data_block 由 Starter 自动建表。
-- 本脚本只写模板元数据与数据块配置，可重复执行（唯一键覆盖）。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('ExcelMergeCells', '竖向合并示例（Excel）', 'XLSX', 'ORIGINAL', 'ENABLED',
        '示例模板：三个工作表分别演示按主键合并 / 按文本合并 / 嵌套循环合并')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'ExcelMergeCells' ORDER BY id LIMIT 1);
SELECT IF(@template_id IS NULL, '模板元数据插入失败', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- ----------------------------------------------------------------- 2. 数据块
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  (@template_id, 'report', '报告主信息', 'SQL',
   '{"sql":"select ''入库明细（竖向合并示例）'' as title, ''1320 件'' as total","single":true,"maxRows":1}',
   '{}', 10, 'ENABLED'),

  (@template_id, 'stock_items', '入库明细（同一物料多批次）', 'SQL',
   '{"sql":"select ''M-1001'' as material_code, ''不锈钢法兰 DN100'' as material_name, ''B2601-A'' as batch_no, ''原料库'' as warehouse, 120 as qty, ''合格'' as result union all select ''M-1001'', ''不锈钢法兰 DN100'', ''B2601-B'', ''原料库'', 120, ''合格'' union all select ''M-1001'', ''不锈钢法兰 DN100'', ''B2601-C'', ''原料库'', 80, ''让步接收'' union all select ''M-1002'', ''石墨缠绕垫片 DN100'', ''B2602-A'', ''半成品库'', 240, ''合格'' union all select ''M-1002'', ''石墨缠绕垫片 DN100'', ''B2602-B'', ''半成品库'', 240, ''合格'' union all select ''M-1003'', ''压力表 Y-100'', ''B2603-A'', null, 40, ''合格'' union all select ''M-1004'', ''六角螺栓套装 M20×90'', ''B2604-A'', null, 480, ''合格''","single":false,"maxRows":500,"mergeCellAlign":"center"}',
   '{}', 20, 'ENABLED'),

  (@template_id, 'orders', '订单', 'SQL',
   '{"sql":"select ''SO-2026-0001'' as order_no, ''杭州云栖智造有限公司'' as customer union all select ''SO-2026-0002'', ''宁波海天精工股份有限公司'' union all select ''SO-2026-0003'', ''苏州汇川技术有限公司''","single":false,"maxRows":100}',
   '{}', 30, 'ENABLED'),

  (@template_id, 'order_items', '订单明细（扁平）', 'SQL',
   '{"sql":"select ''SO-2026-0001'' as order_no, ''不锈钢法兰 DN100'' as sku, 120 as qty union all select ''SO-2026-0001'', ''石墨缠绕垫片 DN100'', 240 union all select ''SO-2026-0001'', ''六角螺栓套装 M20×90'', 480 union all select ''SO-2026-0002'', ''球阀 Q41F-16P DN50'', 60 union all select ''SO-2026-0002'', ''法兰垫片 DN50'', 120 union all select ''SO-2026-0003'', ''压力表 Y-100'', 40","single":false,"maxRows":500}',
   '{}', 40, 'ENABLED')
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
