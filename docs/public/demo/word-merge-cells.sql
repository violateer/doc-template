-- ============================================================================
-- 示例模板：竖向合并单元格（Word，template_code = WordMergeCells，file_type = DOCX）
-- 模板文件：docs/public/demo/word-merge-cells.docx
--
-- 三张表分别演示三种合并写法：
--   写法 1  区域循环 + merge=1,2 by=material_code   按物料主键合并编码与名称两列
--   写法 2  区域循环 + merge=1                      不写 by，按渲染后的文本比较；空值不合并
--   写法 3  嵌套循环 + merge=1 by=order_no           外层列跨内层展开的多行合并
--
-- 4 个数据块（全部 SQL）：
--   report        single=true   标题 / 入库合计 / 说明
--   stock_items   single=false  7 行入库明细（同一物料多批次、含两行空仓库）
--   orders        single=false  3 个订单
--   order_items   single=false  6 行扁平明细（写法 3 用 where 关联过滤）
--
-- 合并规则（三个写法都适用）：
--   只在声明它的那一次循环展开里合并；只合并相邻行；分组键相同才合并；
--   键为空的格子永不合并；一段至少 2 行才真的合并。
--
-- 前置条件：MySQL 8 / MariaDB；doc_template / doc_template_data_block 由 Starter 自动建表。
-- 本脚本只写模板元数据与数据块配置，可重复执行（唯一键覆盖）。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('WordMergeCells', '竖向合并示例（Word）', 'DOCX', 'ORIGINAL', 'ENABLED',
        '示例模板：按主键合并 / 按文本合并 / 嵌套循环里合并外层列')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'WordMergeCells' ORDER BY id LIMIT 1);
SELECT IF(@template_id IS NULL, '模板元数据插入失败', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- ----------------------------------------------------------------- 2. 数据块
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  (@template_id, 'report', '报告主信息', 'SQL',
   '{"sql":"select ''入库明细（竖向合并示例）'' as title, ''1320 件'' as total, ''merge 的列：Word 用 1 起始的单元格序号（merge=1,2），Excel 用列字母或 1 起始列号（merge=A,B / merge=1,3）。'' as note","single":true,"maxRows":1}',
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
