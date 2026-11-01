-- ============================================================================
-- 示例模板：字段值转换 + 数据字典（Word，template_code = WordDictConvert，file_type = DOCX）
-- 模板文件：docs/public/demo/word-dict-convert.docx
--
-- 这个示例演示两条转换路径，两边都不重叠，方便对照：
--   数据块 report 的 converters  → 下单时间 / 订单状态 / 支付方式 / 金额 / 折扣率 / 加急 / 电话
--   模板占位符 ${字段|转换器}     → 明细的单价 / 小计 / 状态，以及合计
--
-- 2 个数据块（全部 SQL）：
--   report   single=true   订单主信息（converters 在数据块里声明）
--   items    single=false  5 行明细（转换写在模板占位符里）
--
-- 3 本字典（脚本会一并建好，可重复执行）：
--   order_status  待确认 / 已付款 / 已发货 / 已完成 / 已取消
--   pay_channel   支付宝 / 微信支付 / 银行转账
--   item_status   正常 / 缺货 / 已换货
--
-- 前置条件：MySQL 8 / MariaDB；doc_template / doc_template_data_block /
--          doc_template_dict / doc_template_dict_item 由 Starter 自动建表。
-- 本脚本只写模板元数据、数据块配置与字典，可重复执行（唯一键覆盖 + 字典项重建）。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('WordDictConvert', '转换与字典示例（Word）', 'DOCX', 'ORIGINAL', 'ENABLED',
        '示例模板：数据块 converters 与模板 ${字段|转换器} 两种字段值转换，含三本数据字典')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'WordDictConvert' ORDER BY id LIMIT 1);
SELECT IF(@template_id IS NULL, '模板元数据插入失败', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- ----------------------------------------------------------------- 2. 数据字典
INSERT INTO doc_template_dict (dict_code, dict_name, remark)
VALUES ('order_status', '订单状态', '示例模板用：订单主表的状态字段'),
       ('pay_channel', '支付方式', '示例模板用：订单的支付渠道'),
       ('item_status', '明细状态', '示例模板用：订单明细的履约状态')
ON DUPLICATE KEY UPDATE dict_name = VALUES(dict_name), remark = VALUES(remark);

SET @dict_order_status = (SELECT id FROM doc_template_dict WHERE dict_code = 'order_status');
SET @dict_pay_channel  = (SELECT id FROM doc_template_dict WHERE dict_code = 'pay_channel');
SET @dict_item_status  = (SELECT id FROM doc_template_dict WHERE dict_code = 'item_status');

-- 字典项按「先清后插」重建，重复执行不会留下旧值
DELETE FROM doc_template_dict_item WHERE dict_id IN (@dict_order_status, @dict_pay_channel, @dict_item_status);

INSERT INTO doc_template_dict_item (dict_id, item_value, item_label, sort_order) VALUES
  (@dict_order_status, 'CREATED',   '待确认', 1),
  (@dict_order_status, 'PAID',      '已付款', 2),
  (@dict_order_status, 'SHIPPED',   '已发货', 3),
  (@dict_order_status, 'DONE',      '已完成', 4),
  (@dict_order_status, 'CANCELLED', '已取消', 5),
  (@dict_pay_channel,  'ALIPAY',    '支付宝',   1),
  (@dict_pay_channel,  'WECHAT',    '微信支付', 2),
  (@dict_pay_channel,  'BANK',      '银行转账', 3),
  (@dict_item_status,  'OK',        '正常',   1),
  (@dict_item_status,  'SHORT',     '缺货',   2),
  (@dict_item_status,  'REPLACED',  '已换货', 3);

-- ----------------------------------------------------------------- 3. 数据块
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  (@template_id, 'report', '订单主信息', 'SQL',
   '{"sql":"select ''订单确认单'' as title, ''SO-2026-1024'' as order_no, ''2026-10-24 09:35:00'' as created_at, ''PAID'' as order_status, ''WECHAT'' as pay_channel, 34908.00 as amount, 0.92 as discount_rate, 1 as is_urgent, ''杭州云栖智造有限公司'' as customer, ''13858001234'' as customer_phone, 34908.00 as total, ''主信息这七个字段的转换都写在数据块 data_source_config.converters 里；明细那一块反过来，转换写在模板占位符上（占位符里就地转换）。两条路径改完立刻生效，不用改 SQL。'' as note","single":true,"maxRows":1,"converters":[{"field":"created_at","type":"date","args":{"pattern":"yyyy-MM-dd HH:mm"}},{"field":"order_status","type":"dict","args":{"code":"order_status"}},{"field":"pay_channel","type":"dict","args":{"code":"pay_channel"}},{"field":"amount","type":"number","args":{"scale":2,"grouping":true}},{"field":"discount_rate","type":"percent","args":{"scale":0}},{"field":"is_urgent","type":"boolean","args":{"trueText":"加急","falseText":"普通"}},{"field":"customer_phone","type":"mask","args":{"mode":"phone"}}]}',
   '{}', 10, 'ENABLED'),

  (@template_id, 'items', '订单明细', 'SQL',
   '{"sql":"select ''不锈钢法兰 DN100'' as sku, 120 as qty, 86.5 as unit_price, 10380.00 as line_total, ''OK'' as item_status union all select ''石墨缠绕垫片 DN100'', 240, 12.8, 3072.00, ''OK'' union all select ''六角螺栓套装 M20×90'', 480, 3.2, 1536.00, ''SHORT'' union all select ''球阀 Q41F-16P DN50'', 60, 268.0, 16080.00, ''OK'' union all select ''压力表 Y-100'', 40, 96.0, 3840.00, ''REPLACED''","single":false,"maxRows":500}',
   '{}', 20, 'ENABLED')
ON DUPLICATE KEY UPDATE
  block_name         = VALUES(block_name),
  data_source_type   = VALUES(data_source_type),
  data_source_config = VALUES(data_source_config),
  param_config       = VALUES(param_config),
  sort_order         = VALUES(sort_order),
  status             = VALUES(status);

-- ----------------------------------------------------------------- 4. 核对
SELECT block_code, block_name, sort_order, status
FROM doc_template_data_block WHERE template_id = @template_id ORDER BY sort_order, id;

SELECT d.dict_code, d.dict_name, COUNT(i.id) AS item_count
FROM doc_template_dict d LEFT JOIN doc_template_dict_item i ON i.dict_id = d.id
WHERE d.dict_code IN ('order_status', 'pay_channel', 'item_status')
GROUP BY d.id, d.dict_code, d.dict_name ORDER BY d.dict_code;
