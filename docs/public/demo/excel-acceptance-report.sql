-- ============================================================================
-- 示例模板：正式版 Excel 报告（template_code = ExcelAcceptanceReport，file_type = XLSX）
-- 模板文件：docs/public/demo/excel-acceptance-report.xlsx
-- 内容：封面 / 交付物清单 / 性能指标 / 遗留问题 / 使用说明
--
-- 所有数据块都用 select ... union all 直接造数，**不建表、不插业务数据**，
-- 也不依赖任何业务库：执行完这个脚本就能直接「模拟生成」，不用填任何参数。
-- 想换成真实查询时，把对应块的 sql 换成自己的 select（保证返回列名不变即可）。
--
--   行循环：某行最后一格写成 ${item.accept_result}{{#each items}}，整行按返回行数复制。
--
-- 5 个数据块（全部 SQL）：
--   report         single=true  主信息（Excel 版没有长文本列）
--   units          single=false 6 行：表格行循环
--   items          single=false 12 行：表格行循环
--   metrics        single=false 8 行：表格行循环
--   issues         single=false 6 行：表格行循环
--
-- 图片：封面 LOGO 是模板里的静态图，不走图片字段；需要动态图时在数据块配置里
--   加 imageFields（如 [{"field":"figure","width":120}]），并让 select 返回该列。
--
-- 前置条件：MySQL 8 / MariaDB；doc_template / doc_template_data_block 由 Starter 自动建表。
-- 本脚本只写模板元数据与数据块配置，可重复执行（唯一键覆盖）。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('ExcelAcceptanceReport', '正式验收报告（Excel）', 'XLSX', 'ORIGINAL', 'ENABLED', '示例模板：多 sheet / 合并单元格 / KPI 卡片 / 冻结窗格 / 打印区域 / 行循环')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'ExcelAcceptanceReport' ORDER BY id LIMIT 1);
SELECT IF(@template_id IS NULL, '模板元数据插入失败', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- ----------------------------------------------------------------- 2. 数据块
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  -- report：主信息（Excel 版没有长文本列）
  (@template_id, 'report', '报告主信息', 'SQL',
   '{"sql":"select ''XXX 项目'' as project_name, ''XXX-2026-0001'' as project_code, ''XXX-HT-2026-0001'' as contract_no, ''XXX 集团'' as client_name, ''XXX 科技有限公司'' as vendor_name, ''XXX-ACC-2026-0001'' as report_no, ''V1.0'' as version, ''2026-10-28'' as report_date, ''2025-11-01'' as period_start, ''2026-09-30'' as period_end, ''XXX'' as author, ''XXX'' as reviewer, ''XXX'' as approver, ''验收组认为：本项目已按合同约定完成全部建设内容，功能实现完整、性能指标优于合同要求、安全合规满足等级保护三级标准，交付文档齐全可用，具备正式投入生产运行的条件。同意本项目通过交付验收，综合评定为「优」。'' as conclusion, ''94.6'' as total_score, ''12'' as item_count, ''100.0%'' as pass_rate, ''6'' as issue_open_count","single":true,"maxRows":1}',
   '{}', 10, 'ENABLED'),
  -- units：6 行：表格行循环
  (@template_id, 'units', '参建单位', 'SQL',
   '{"sql":"select ''XXX 集团信息中心'' as unit_name, ''建设单位（甲方）'' as unit_role, ''XXX'' as leader, ''立项、需求确认、验收组织与资源协调'' as duty union all select ''XXX 科技有限公司'', ''总承建单位'', ''XXX'', ''总体设计、平台开发、系统集成与整体交付'' union all select ''XXX 分包单位 A'', ''分包单位'', ''XXX'', ''安防子系统与门禁、视频设备对接调试'' union all select ''XXX 分包单位 B'', ''分包单位'', ''XXX'', ''能源采集网关与表计接入校准'' union all select ''XXX 监理公司'', ''监理单位'', ''XXX'', ''过程监理、质量抽查与验收见证'' union all select ''XXX 集团运营管理部'', ''业务使用部门'', ''XXX'', ''业务规则确认、用户测试与培训组织''","single":false,"maxRows":100}',
   '{}', 20, 'ENABLED'),
  -- items：12 行：表格行循环
  (@template_id, 'items', '交付物清单', 'SQL',
   '{"sql":"select ''01'' as seq_no, ''主数据平台'' as item_name, ''软件系统'' as item_type, ''V1.0.0'' as version, ''2026-08-20'' as deliver_date, ''通过'' as accept_result union all select ''02'', ''综合安防子系统'', ''软件系统'', ''V1.0.0'', ''2026-08-28'', ''通过'' union all select ''03'', ''能源管理子系统'', ''软件系统'', ''V1.0.0'', ''2026-09-05'', ''通过'' union all select ''04'', ''运营服务子系统'', ''软件系统'', ''V1.0.0'', ''2026-09-10'', ''通过'' union all select ''05'', ''可视化驾驶舱'', ''软件系统'', ''V1.0.0'', ''2026-09-12'', ''通过'' union all select ''06'', ''单点登录接口'', ''接口与集成'', ''V1.0'', ''2026-09-15'', ''通过'' union all select ''07'', ''财务数据接口'', ''接口与集成'', ''V1.0'', ''2026-09-16'', ''通过'' union all select ''08'', ''人力资源主数据接口'', ''接口与集成'', ''V1.1'', ''2026-09-18'', ''通过'' union all select ''09'', ''主数据标准与清洗成果包'', ''数据资产'', ''V1.0'', ''2026-09-20'', ''通过'' union all select ''10'', ''设计与运维文档集'', ''文档资料'', ''V1.0'', ''2026-09-25'', ''通过'' union all select ''11'', ''用户操作手册与培训材料'', ''文档资料'', ''V1.0'', ''2026-09-26'', ''通过'' union all select ''12'', ''培训与运维交接服务'', ''培训与服务'', ''—'', ''2026-09-30'', ''通过''","single":false,"maxRows":200}',
   '{}', 30, 'ENABLED'),
  -- metrics：8 行：表格行循环
  (@template_id, 'metrics', '性能指标', 'SQL',
   '{"sql":"select ''容量类'' as category, ''支持在线用户数'' as metric_name, ''≥ 300 并发'' as target_value, ''342 并发'' as actual_value, ''优于目标'' as conclusion union all select ''响应类'', ''登录接口平均响应'', ''≤ 800 ms'', ''412 ms'', ''满足'' union all select ''响应类'', ''列表查询 95 分位'', ''≤ 3000 ms'', ''1860 ms'', ''满足'' union all select ''响应类'', ''首页首屏加载'', ''≤ 5000 ms'', ''3280 ms'', ''满足'' union all select ''稳定性'', ''连续压测 2 小时错误率'', ''≤ 0.1%'', ''0.02%'', ''满足'' union all select ''容量类'', ''单日工单处理量'', ''≥ 5000 单'', ''6840 单'', ''优于目标'' union all select ''数据类'', ''主数据同步延迟'', ''≤ 5 s'', ''2.4 s'', ''满足'' union all select ''资源类'', ''应用节点 CPU 峰值'', ''≤ 75%'', ''61%'', ''满足''","single":false,"maxRows":100}',
   '{}', 40, 'ENABLED'),
  -- issues：6 行：表格行循环
  (@template_id, 'issues', '遗留问题', 'SQL',
   '{"sql":"select ''ISS-0001'' as issue_no, ''短信通知在部分运营商下延迟超过 5 分钟'' as description, ''二级'' as level, ''承建单位'' as owner, ''2026-11-10'' as plan_date, ''整改中'' as status union all select ''ISS-0002'', ''看板缺少按楼层下钻的权限过滤'', ''一级'', ''承建单位'', ''2026-10-31'', ''整改中'' union all select ''ISS-0003'', ''移动端在低版本客户端下样式错位'', ''二级'', ''承建单位'', ''2026-11-15'', ''待排期'' union all select ''ISS-0004'', ''接口文档变更记录未补齐'', ''三级'', ''承建单位'', ''2026-11-30'', ''待排期'' union all select ''ISS-0005'', ''报表导出超 5 万行时偶发超时'', ''二级'', ''承建单位'', ''2026-11-20'', ''整改中'' union all select ''ISS-0006'', ''建议增加工单超时自动升级提醒'', ''三级'', ''委托单位'', ''2027-01-31'', ''已确认''","single":false,"maxRows":100}',
   '{}', 50, 'ENABLED')
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
