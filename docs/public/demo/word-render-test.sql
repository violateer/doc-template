-- ============================================================================
-- 示例模板：普通Word（template_code = WordStandard，file_type = DOCX，export_type = ORIGINAL）
-- 模板文件：docs/public/demo/word-render-test.docx（原文件名 word渲染测试.docx）
--
-- 三个数据块：
--   form          SQL    —— 标题与信息，不查任何表，直接 select 假数据
--   demoProvider  JAVA   —— 调 example 模块的 DemoOrderProvider（编码 demoOrder）
--   demoApi       API    —— 调 example 模块的 POST /demo/api/orders/{orderNo}，
--                           同时演示 URL 传参（路径 {orderNo} + 查询串 ${channel}）和 body 传参
--
-- 模拟生成时要填两个参数：orderNo（demoProvider 用）、channel（demoApi 用）。
-- 「补齐参数」只会列出参数映射里以 $request. 开头的键，写死的常量不会列出来。
--
-- 前置条件：
--   * MySQL 8 / MariaDB，已建好 doc_template / doc_template_data_block（Starter 会自动建）；
--   * form 块不依赖任何业务表，执行完就能生成文档；
--   * demoProvider / demoApi 依赖 doc-template-example 已启动，且端口是 9150（不是就改 url）。
-- 可重复执行：唯一键 (template_id, block_code)，重跑会覆盖成同一份配置。
-- ============================================================================

-- 1) 模板元数据。已存在时不动名称，避免覆盖线上配置
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('WordStandard', '普通Word', 'DOCX', 'ORIGINAL', 'ENABLED', '示例模板：三个数据块 + 明细行循环')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'WordStandard' ORDER BY id LIMIT 1);

SELECT IF(@template_id IS NULL, '模板元数据插入失败，请检查 doc_template 表', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- 2) 数据块。执行顺序 = sort_order：先 form，再 demoProvider，最后 demoApi
INSERT INTO doc_template_data_block
    (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
    -- 标题与信息：不查表，直接 select 两列假数据；single=true 返回单条对象，
    -- 模板里的 ${form.title} / ${form.info} 就能取到值。
    -- 换成真实查询时，保证结果里有 title / info 这两列即可。
    (@template_id, 'form', '信息', 'SQL',
     '{"sql":"select ''数据块渲染测试'' as title, ''示例数据：这条 SQL 不查任何表'' as info","single":true,"maxRows":1}',
     '{}', 1, 'ENABLED'),

    -- Java Provider：参数映射把生成时传的 orderNo 交给 Provider，不传就用 Provider 的默认单号
    (@template_id, 'demoProvider', '演示订单（Java Provider）', 'JAVA',
     '{"provider":"demoOrder"}',
     '{"orderNo":"$request.orderNo"}', 10, 'ENABLED'),

    -- API：URL 和 body 可以同时传参
    --   地址写 host.docker.internal:9150：演示站跑在容器里，这个请求是服务端自己发出去的，
    --   走宿主机映射端口最稳（compose 里已加 extra_hosts: host-gateway）。
    --   本机直接 mvn spring-boot:run 时把它改成 http://127.0.0.1:9150 即可。
    --   url 里的 {orderNo} 走路径、${channel} 走查询串（两种写法都认）；
    --   body 里的 ${channel} 也会替换，整串就是一个 ${name} 时还能直接塞对象 / 数组；
    --   resultPath=data 只取响应里的业务部分，接口会把收到的 body 回显在 request_body 里。
    -- 参数映射：orderNo 是常量（API 块固定查这个单号），channel 走 $request.channel，
    --   所以「模拟生成」的补齐参数里会出现 channel，填什么就传什么；
    --   想让 API 块也用生成时传的单号，把 orderNo 的值改成 $request.orderNo 即可。
    (@template_id, 'demoApi', '演示订单（API 接口）', 'API',
     '{"url":"http://host.docker.internal:9150/demo/api/orders/{orderNo}?name=${channel}","method":"POST","timeoutMs":5000,"resultPath":"data","headers":{"X-Demo-Source":"doc-template"},"body":{"channel":"${channel}","remark":"body 传参示例"}}',
     '{"orderNo":"DEMO-2026-0001","channel":"$request.channel"}', 20, 'ENABLED')
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
