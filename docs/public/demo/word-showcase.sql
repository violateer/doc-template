-- ============================================================================
-- 示例模板：综合示例（Word，template_code = WordShowcase，file_type = DOCX）
-- 模板文件：docs/public/demo/word-showcase.docx
--
-- 一份文档把模板的全部写法摆在一起，生成一次就能逐条对照：
--   一、占位符写法      美元花括号 / 双层花括号 / 默认值 / 手填参数 / 点号取嵌套
--   二、明细行循环      行内标记 / 区域循环 / 段落块循环
--   三、嵌套循环        外层项自带 items / 两个扁平数组用 where 关联
--   四、竖向合并        merge=1,2 by=material_code / 只写 merge 按文本比较
--   五、字段值转换      数据块 converters（含字典、宿主自定义转换器）+ 占位符就地转换
--   六、图片与二维码    imageFields / qrCodes（二维码中间叠同块另一个字段的小图）
--   七、三种数据源      JAVA Provider / API / SQL
--   八、动态列          列定义由列源决定：固定列混排 / 两行分组表头 / 列级宽度与对齐 / span 静态行
--
-- 8 个数据块：report / items / orders / order_items / stock_items / inspection（SQL）、
--             demoProvider（JAVA）、demoApi（API）
--             inspection 的 data_source_config 里多挂了一个 columns（列源），列数由它决定
-- 3 本字典：order_status / pay_channel / item_status（与 Excel 那份共用，脚本幂等）
--
-- 模拟生成时要填的参数（参数映射里以 $request. 开头的键）：
--   inspector  模板里手填的审核人，不填显示「未填写」
--   orderNo    demoProvider 用，不传走 Provider 默认单号
--   channel    demoApi 用，会出现在查询串和 body 里
--
-- 前置条件：
--   * MySQL 8 / MariaDB，相关表由 Starter 自动建；
--   * demoProvider / demoApi 依赖 doc-template-example 已启动，且端口是 9150
--     （demoApi 回调的是 127.0.0.1:9150，容器里自调也成立；若接口挂在别的地址，改 url 即可）；
--   * 图片与二维码的图源是外网地址，离线环境请换成本地或 base64。
--
-- 可重复执行：唯一键 (template_id, block_code)，重跑覆盖成同一份配置。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('WordShowcase', '综合示例（Word）', 'DOCX', 'ORIGINAL', 'ENABLED',
        '示例模板：占位符 / 循环 / 嵌套 / 合并 / 转换 / 图片与二维码的集中演示')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'WordShowcase' ORDER BY id LIMIT 1);
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
-- 执行顺序 = sort_order；结果以块编码做命名空间，模板里写 ${report.xxx}
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  -- 主信息：7 个字段的转换写在 converters 里；logo 声明成图片字段，verify_url 声明成二维码字段，
  -- 二维码中间的小图取同块的 qr_logo 字段。
  (@template_id, 'report', '主信息（转换 / 图片 / 二维码）', 'SQL',
   '{"sql":"select ''Word 示例模板'' as title, ''占位符、循环、转换、合并、图片与二维码的集中演示'' as subtitle, ''WS-2026-0001'' as report_no, ''2026-10-28'' as report_date, ''张三'' as author, ''2026-10-24 09:35:00'' as created_at, ''PAID'' as order_status, ''WECHAT'' as pay_channel, 34908.00 as amount, 0.92 as discount_rate, 1 as is_urgent, ''13858001234'' as customer_phone, ''杭州云栖智造有限公司'' as customer, ''M-1001'' as material_code, 34908.00 as total, 1320 as total_qty, ''https://07akioni.oss-cn-beijing.aliyuncs.com/07akioni.jpeg'' as logo, ''https://doc-template.example.com/verify?no=WS-2026-0001'' as verify_url, ''https://07akioni.oss-cn-beijing.aliyuncs.com/07akioni.jpeg'' as qr_logo","single":true,"maxRows":1,"converters":[{"field":"created_at","type":"date","args":{"pattern":"yyyy-MM-dd HH:mm"}},{"field":"order_status","type":"dict","args":{"code":"order_status"}},{"field":"pay_channel","type":"dict","args":{"code":"pay_channel"}},{"field":"amount","type":"number","args":{"scale":2,"grouping":true}},{"field":"total","type":"number","args":{"scale":2,"grouping":true}},{"field":"discount_rate","type":"percent","args":{"scale":0}},{"field":"is_urgent","type":"boolean","args":{"trueText":"加急","falseText":"普通"}},{"field":"customer_phone","type":"mask","args":{"mode":"phone"}},{"field":"material_code","type":"demoSku"}],"imageFields":[{"field":"logo","width":120,"align":"center"}],"qrCodes":[{"field":"verify_url","width":140,"logoField":"qr_logo"}]}',
   '{}', 10, 'ENABLED'),

  -- 明细：转换写在模板占位符上（单价 / 小计 / 状态），和主信息那条路径形成对照
  (@template_id, 'items', '订单明细（占位符就地转换）', 'SQL',
   '{"sql":"select ''不锈钢法兰 DN100'' as sku, 120 as qty, 86.5 as unit_price, 10380.00 as line_total, ''OK'' as item_status union all select ''石墨缠绕垫片 DN100'', 240, 12.8, 3072.00, ''OK'' union all select ''六角螺栓套装 M20×90'', 480, 3.2, 1536.00, ''SHORT'' union all select ''球阀 Q41F-16P DN50'', 60, 268.0, 16080.00, ''OK'' union all select ''压力表 Y-100'', 40, 96.0, 3840.00, ''REPLACED''","single":false,"maxRows":500}',
   '{}', 20, 'ENABLED'),

  -- 嵌套：外层每行自带一个 items 数组（JSON_ARRAY 拼成 JSON 文本，渲染时自动解析）
  (@template_id, 'orders', '订单（自带 items 数组）', 'SQL',
   '{"sql":"select ''SO-2026-0001'' as order_no, ''杭州云栖智造有限公司'' as customer, ''15180.00'' as amount, cast(json_array(json_object(''sku'',''不锈钢法兰 DN100'',''qty'',120,''price'',''86.50''), json_object(''sku'',''石墨缠绕垫片 DN100'',''qty'',240,''price'',''12.80''), json_object(''sku'',''六角螺栓套装 M20×90'',''qty'',480,''price'',''3.60'')) as char) as items union all select ''SO-2026-0002'', ''宁波海天精工股份有限公司'', ''10224.00'', cast(json_array(json_object(''sku'',''球阀 Q41F-16P DN50'',''qty'',60,''price'',''158.00''), json_object(''sku'',''法兰垫片 DN50'',''qty'',120,''price'',''6.20'')) as char) union all select ''SO-2026-0003'', ''苏州汇川技术有限公司'', ''1680.00'', cast(json_array(json_object(''sku'',''压力表 Y-100'',''qty'',40,''price'',''42.00'')) as char)","single":false,"maxRows":100}',
   '{}', 30, 'ENABLED'),

  -- 扁平明细：和 orders 按 order_no 关联，模板里用 where 过滤
  (@template_id, 'order_items', '订单明细（扁平，where 关联）', 'SQL',
   '{"sql":"select ''SO-2026-0001'' as order_no, ''不锈钢法兰 DN100'' as sku, 120 as qty, ''86.50'' as price, ''10380.00'' as amount union all select ''SO-2026-0001'', ''石墨缠绕垫片 DN100'', 240, ''12.80'', ''3072.00'' union all select ''SO-2026-0001'', ''六角螺栓套装 M20×90'', 480, ''3.60'', ''1728.00'' union all select ''SO-2026-0002'', ''球阀 Q41F-16P DN50'', 60, ''158.00'', ''9480.00'' union all select ''SO-2026-0002'', ''法兰垫片 DN50'', 120, ''6.20'', ''744.00'' union all select ''SO-2026-0003'', ''压力表 Y-100'', 40, ''42.00'', ''1680.00''","single":false,"maxRows":500}',
   '{}', 40, 'ENABLED'),

  -- 竖向合并：同一物料多批次，mergeCellAlign 让合并格垂直居中
  (@template_id, 'stock_items', '入库明细（竖向合并）', 'SQL',
   '{"sql":"select ''M-1001'' as material_code, ''不锈钢法兰 DN100'' as material_name, ''B2601-A'' as batch_no, ''原料库'' as warehouse, 120 as qty, ''合格'' as result union all select ''M-1001'', ''不锈钢法兰 DN100'', ''B2601-B'', ''原料库'', 120, ''合格'' union all select ''M-1001'', ''不锈钢法兰 DN100'', ''B2601-C'', ''原料库'', 80, ''让步接收'' union all select ''M-1002'', ''石墨缠绕垫片 DN100'', ''B2602-A'', ''半成品库'', 240, ''合格'' union all select ''M-1002'', ''石墨缠绕垫片 DN100'', ''B2602-B'', ''半成品库'', 240, ''合格'' union all select ''M-1003'', ''压力表 Y-100'', ''B2603-A'', null, 40, ''合格'' union all select ''M-1004'', ''六角螺栓套装 M20×90'', ''B2604-A'', null, 480, ''合格''","single":false,"maxRows":500,"mergeCellAlign":"center"}',
   '{}', 50, 'ENABLED'),

  -- 动态列：行数据是「检验项目」，列定义由 columns 里的另一个 SQL 给（3 列样品）。
  -- 列源的参数映射与主数据共用块级 param_config；single 对列源没有意义，配了也忽略。
  -- 元素四个键：field（取值字段名，必填）/ label（列标题）/ width（相对权重）/ align（left|center|right）
  (@template_id, 'inspection', '检验结果（动态列）', 'SQL',
   '{"sql":"select ''抗拉强度'' as item_name, ''MPa'' as unit, 520 as s1, 515 as s2, 530 as s3 union all select ''伸长率'', ''%'', 28, 29, 27 union all select ''硬度'', ''HB'', 180, 178, 182 union all select ''冲击功（-20℃）'', ''J'', 46, 44, 47","single":false,"maxRows":500,"columns":{"type":"SQL","sql":"select ''s1'' as field, ''样品 1#'' as label, 1 as width union all select ''s2'', ''样品 2#'', 1 union all select ''s3'', ''样品 3#'', 2"}}',
   '{}', 55, 'ENABLED'),

  -- JAVA Provider：参数映射把生成时传的 orderNo 交给 Provider，不传就用 Provider 的默认单号
  (@template_id, 'demoProvider', '订单（Java Provider）', 'JAVA',
   '{"provider":"demoOrder"}',
   '{"orderNo":"$request.orderNo"}', 60, 'ENABLED'),

  -- API：路径 {orderNo}、查询串 ${channel}、body 里的 ${channel} 三处同时传参；
  -- resultPath=data 只取响应里的业务部分。回调地址用 127.0.0.1:9150 —— 示例宿主自己就是 9150，
  -- 容器里自调同样成立；接口挂在别的地址时改 url 即可。
  (@template_id, 'demoApi', '订单（API 接口）', 'API',
   '{"url":"http://127.0.0.1:9150/demo/api/orders/{orderNo}?name=${channel}","method":"POST","timeoutMs":5000,"resultPath":"data","headers":{"X-Demo-Source":"doc-template"},"body":{"channel":"${channel}","remark":"body 传参示例"}}',
   '{"orderNo":"DEMO-2026-0001","channel":"$request.channel"}', 70, 'ENABLED')
ON DUPLICATE KEY UPDATE
  block_name         = VALUES(block_name),
  data_source_type   = VALUES(data_source_type),
  data_source_config = VALUES(data_source_config),
  param_config       = VALUES(param_config),
  sort_order         = VALUES(sort_order),
  status             = VALUES(status);

-- ----------------------------------------------------------------- 4. 核对
SELECT block_code, block_name, data_source_type, sort_order, status
FROM doc_template_data_block WHERE template_id = @template_id ORDER BY sort_order, id;

SELECT d.dict_code, d.dict_name, COUNT(i.id) AS item_count
FROM doc_template_dict d LEFT JOIN doc_template_dict_item i ON i.dict_id = d.id
WHERE d.dict_code IN ('order_status', 'pay_channel', 'item_status')
GROUP BY d.id, d.dict_code, d.dict_name ORDER BY d.dict_code;
