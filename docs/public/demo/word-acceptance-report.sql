-- ============================================================================
-- 示例模板：正式版 Word 报告（template_code = WordAcceptanceReport，file_type = DOCX）
-- 模板文件：docs/public/demo/word-acceptance-report.docx
-- 内容：封面 / 文档控制页 / 目录域 / 6 章正文 / 附录 A~C / 签署页
--
-- 所有数据块都用 select ... union all 直接造数，**不建表、不插业务数据**，
-- 也不依赖任何业务库：执行完这个脚本就能直接「模拟生成」，不用填任何参数。
-- 想换成真实查询时，把对应块的 sql 换成自己的 select（保证返回列名不变即可）。
--
--   表格行循环：某行最后一格写成 ${item.c1}{{#each revisions}}，整行按返回行数复制；
--   段落块循环：{{#each goals}} 与 {{/each}} 各自独占一段，两段之间的段落整体重复。
--
-- 20 个数据块（全部 SQL）：
--   report         single=true  主信息 + 9 段长文本 + 图片地址
--   units          single=false 6 行：表格行循环
--   items          single=false 12 行：表格行循环
--   metrics        single=false 8 行：表格行循环
--   issues         single=false 6 行：表格行循环
--   revisions      single=false 4 行：表格行循环
--   distribution   single=false 4 行：表格行循环
--   goals          single=false 4 行：段落块循环
--   scope          single=false 6 行：段落块循环
--   checklist      single=false 3 行：段落块循环
--   basis          single=false 5 行：段落块循环
--   methods        single=false 6 行：表格行循环
--   criteria       single=false 4 行：段落块循环
--   security       single=false 3 行：段落块循环
--   issue_levels   single=false 3 行：表格行循环
--   scores         single=false 6 行：表格行循环
--   suggestions    single=false 4 行：段落块循环
--   glossary       single=false 6 行：表格行循环
--   ref_docs       single=false 6 行：表格行循环
--   signers        single=false 5 行：表格行循环
--
-- 图片字段：report 块返回 figure 列（http 地址），配置里用 imageFields 登记，
--   引擎会下载后按 420px 宽等比嵌入。换成 Base64 或 select 出来的 byte[] 同样支持。
--
-- 前置条件：MySQL 8 / MariaDB；doc_template / doc_template_data_block 由 Starter 自动建表。
-- 本脚本只写模板元数据与数据块配置，可重复执行（唯一键覆盖）。
-- ============================================================================

-- ----------------------------------------------------------------- 1. 模板元数据
INSERT INTO doc_template (template_code, template_name, file_type, export_type, status, remark)
VALUES ('WordAcceptanceReport', '正式验收报告（Word）', 'DOCX', 'ORIGINAL', 'ENABLED', '示例模板：封面 / 目录域 / 多级标题 / 斑马纹表格 / 图片字段 / 表格行循环 / 段落块循环')
ON DUPLICATE KEY UPDATE template_code = VALUES(template_code);

SET @template_id = (SELECT id FROM doc_template WHERE template_code = 'WordAcceptanceReport' ORDER BY id LIMIT 1);
SELECT IF(@template_id IS NULL, '模板元数据插入失败', CONCAT('目标模板 id = ', @template_id)) AS check_result;

-- ----------------------------------------------------------------- 2. 数据块
INSERT INTO doc_template_data_block
  (template_id, block_code, block_name, data_source_type, data_source_config, param_config, sort_order, status)
VALUES
  -- report：主信息 + 9 段长文本 + 图片地址
  (@template_id, 'report', '报告主信息', 'SQL',
   '{"sql":"select ''XXX 项目'' as project_name, ''XXX-2026-0001'' as project_code, ''XXX-HT-2026-0001'' as contract_no, ''XXX 集团'' as client_name, ''XXX 科技有限公司'' as vendor_name, ''XXX-ACC-2026-0001'' as report_no, ''V1.0'' as version, ''2026-10-28'' as report_date, ''2025-11-01'' as period_start, ''2026-09-30'' as period_end, ''XXX'' as author, ''XXX'' as reviewer, ''XXX'' as approver, ''验收组认为：本项目已按合同约定完成全部建设内容，功能实现完整、性能指标优于合同要求、安全合规满足等级保护三级标准，交付文档齐全可用，具备正式投入生产运行的条件。同意本项目通过交付验收，综合评定为「优」。'' as conclusion, ''94.6'' as total_score, ''12'' as item_count, ''100.0%'' as pass_rate, ''6'' as issue_open_count, ''项目于 2025 年 11 月启动，2026 年 9 月完成建设并转入试运行。建设内容覆盖主数据、综合安防、能源管理、运营服务与可视化驾驶舱五个子系统，验收工作于 2026 年 10 月 12 日至 23 日完成，形成本报告。'' as background, ''交付物分为软件系统、接口与集成、数据资产、文档资料、培训与服务五类，共 12 项，全部通过验收，验收通过率 100.0%。其中软件系统 5 项、接口与集成 3 项、数据资产 1 项、文档资料 2 项、培训与服务 1 项。'' as delivery_note, ''验收累计投入 46 人日，形成测试记录 128 份、问题跟踪单 31 张，覆盖功能、性能、安全与文档四类检查项。总体情况如下：'' as exec_summary, ''抽样复测 39 个功能点全部通过；两条主链路各连续执行 20 次，业务闭环成功率 100%；五个子系统的核心场景均按预期完成端到端演示。'' as function_summary, ''注：压测环境为 4 节点应用集群 + 8 核 32G 数据库，数据量为生产环境的 1.2 倍。'' as perf_env, ''一票否决项：数据安全、资金结算、消防联动三类功能任一项不通过，本次验收即判为不通过，不适用加权平均。'' as veto_rule, ''整改按「清单 + 责任人 + 时限」跟踪，闭环需提交整改说明与复测记录；逾期未完成的按合同违约条款处理，并在下次复验时扣减相应分值。'' as tracking_note, ''自本报告签署之日起系统转入生产运行，进入 6 个月质保期。质保期内由承建单位负责缺陷修复与版本升级，重大故障响应时间不超过 2 小时。'' as warranty_note, ''项目标识随交付物一并移交，使用时保持比例、不得拉伸变形，最小使用尺寸 12 毫米；深色背景下须使用反白版本。'' as logo_note, ''系统版本：核心 V1.0.0（构建号 20260928.3），前端 V1.0.0，数据库 V1.0.0（Flyway 基线 128）。'' as version_note, ''本报告经以下各方签署后生效，签署即表示对验收结论的确认。报告一式肆份，委托单位执贰份，承建单位、监理单位各执壹份，具有同等效力。'' as sign_note, ''https://07akioni.oss-cn-beijing.aliyuncs.com/07akioni.jpeg'' as figure","single":true,"maxRows":1,"imageFields":[{"field":"figure","width":420,"align":"center"}]}',
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
   '{}', 50, 'ENABLED'),
  -- revisions：4 行：表格行循环
  (@template_id, 'revisions', '修订记录', 'SQL',
   '{"sql":"select ''V0.7'' as c1, ''2026-10-09'' as c2, ''初稿，完成项目概述与交付物清单'' as c3, ''XXX'' as c4 union all select ''V0.8'', ''2026-10-16'', ''补充性能压测数据与安全测评结论'', ''XXX'' union all select ''V0.9'', ''2026-10-21'', ''按验收组意见补充遗留问题与整改计划'', ''XXX'' union all select ''V1.0'', ''2026-10-28'', ''按评审意见修订后形成发布版，提交签署'', ''XXX''","single":false,"maxRows":100}',
   '{}', 60, 'ENABLED'),
  -- distribution：4 行：表格行循环
  (@template_id, 'distribution', '分发范围', 'SQL',
   '{"sql":"select ''XXX 集团信息中心'' as c1, ''2'' as c2, ''纸质 + 电子'' as c3, ''归档与备案'' as c4 union all select ''XXX 集团审计部'', ''1'', ''电子'', ''项目审计'' union all select ''XXX 集团运营管理部'', ''1'', ''电子'', ''业务移交'' union all select ''XXX 科技有限公司'', ''1'', ''电子'', ''项目结项''","single":false,"maxRows":100}',
   '{}', 70, 'ENABLED'),
  -- goals：4 行：段落块循环
  (@template_id, 'goals', '建设目标', 'SQL',
   '{"sql":"select ''统一主数据：组织、人员、空间、设备四类主数据一致率 ≥ 99%。'' as c1 union all select ''提升效率：工单从受理到派单平均时长 ≤ 10 分钟，闭环率 ≥ 95%。'' union all select ''支撑决策：四类核心指标按日更新，支持按楼层与专业下钻。'' union all select ''可持续演进：新增子系统 2 周内完成接入，不改动既有接口。''","single":false,"maxRows":100}',
   '{}', 80, 'ENABLED'),
  -- scope：6 行：段落块循环
  (@template_id, 'scope', '建设范围', 'SQL',
   '{"sql":"select ''1'' as seq_no, ''主数据平台：建模、清洗、同步与质量管理。'' as c1 union all select ''2'', ''综合安防：视频、门禁、访客、周界与事件联动。'' union all select ''3'', ''能源管理：四类表计采集、分项计量与异常告警。'' union all select ''4'', ''运营服务：门户、工单、空间预约与满意度评价。'' union all select ''5'', ''可视化驾驶舱：一张图、指标看板与专题分析。'' union all select ''6'', ''系统集成：单点登录与 24 个数据接口。''","single":false,"maxRows":100}',
   '{}', 90, 'ENABLED'),
  -- checklist：3 行：段落块循环
  (@template_id, 'checklist', '完整性核对', 'SQL',
   '{"sql":"select ''1'' as seq_no, ''完整性'' as c1, ''清单 12 项全部提交，功能点 386 个全部实现。'' as c2 union all select ''2'', ''一致性'', ''源码版本与生产部署版本一致，抽查 10 个制品全部吻合。'' union all select ''3'', ''可追溯性'', ''抽查 30 条需求，需求到测试的追溯完整率 100%。''","single":false,"maxRows":100}',
   '{}', 100, 'ENABLED'),
  -- basis：5 行：段落块循环
  (@template_id, 'basis', '验收依据', 'SQL',
   '{"sql":"select ''1'' as seq_no, ''项目合同及附件一至附件四。'' as c1 union all select ''2'', ''需求规格说明书 V1.2 及三次变更确认单。'' union all select ''3'', ''集团《信息化项目验收管理办法》。'' union all select ''4'', ''GB/T 25000.51—2016 系统与软件质量要求和评价。'' union all select ''5'', ''GB/T 22239—2019 网络安全等级保护基本要求（第三级）。''","single":false,"maxRows":100}',
   '{}', 110, 'ENABLED'),
  -- methods：6 行：表格行循环
  (@template_id, 'methods', '验收方法', 'SQL',
   '{"sql":"select ''文档审查'' as c1, ''审查需求、设计、测试、运维四类文档'' as c2, ''验收组、监理单位'' as c3, ''10-12 至 10-13'' as c4 union all select ''功能演示'', ''端到端演示五个子系统的核心场景'', ''承建单位、业务部门'', ''10-14 至 10-15'' union all select ''抽样测试'', ''按 10% 比例抽取 39 个功能点复测'', ''验收组'', ''10-16'' union all select ''性能压测'', ''300 并发下验证核心接口响应与长尾分布'', ''验收组、承建单位'', ''10-19'' union all select ''安全扫描'', ''漏洞扫描、渗透测试与主机基线核查'', ''第三方安全机构'', ''10-20 至 10-21'' union all select ''用户访谈'', ''随机访谈 24 名试运行用户并回收问卷'', ''验收组'', ''10-22''","single":false,"maxRows":100}',
   '{}', 120, 'ENABLED'),
  -- criteria：4 行：段落块循环
  (@template_id, 'criteria', '验收标准', 'SQL',
   '{"sql":"select ''1'' as seq_no, ''功能：386 个功能点全部实现，抽样复测通过率 100%。'' as c1 union all select ''2'', ''性能：300 并发平均响应 ≤ 1.5 秒，95 分位 ≤ 3 秒。'' union all select ''3'', ''安全：无高危漏洞，等保三级符合率 ≥ 95%。'' union all select ''4'', ''文档：交付齐全、签署完整，可支撑第三方接手运维。''","single":false,"maxRows":100}',
   '{}', 130, 'ENABLED'),
  -- security：3 行：段落块循环
  (@template_id, 'security', '安全与文档', 'SQL',
   '{"sql":"select ''1'' as seq_no, ''漏洞扫描'' as c1, ''高危 0 个，中危 3 个已修复复测，低危 11 个已修 8 个。'' as c2 union all select ''2'', ''等级保护'', ''三级要求项 78 项，符合 75 项，符合率 96.2%。'' union all select ''3'', ''交付文档'', ''8 类 21 册，抽查 6 册与系统实际一致。''","single":false,"maxRows":100}',
   '{}', 140, 'ENABLED'),
  -- issue_levels：3 行：表格行循环
  (@template_id, 'issue_levels', '问题分级', 'SQL',
   '{"sql":"select ''一级'' as c1, ''影响主流程或存在数据安全风险'' as c2, ''3 个工作日内'' as c3, ''每日跟踪，验收组复核'' as c4 union all select ''二级'', ''影响部分体验，有替代方案'', ''15 个工作日内'', ''每周跟踪，项目经理确认'' union all select ''三级'', ''优化类建议，不影响业务开展'', ''纳入版本规划'', ''月度评审''","single":false,"maxRows":100}',
   '{}', 150, 'ENABLED'),
  -- scores：6 行：表格行循环
  (@template_id, 'scores', '验收评分', 'SQL',
   '{"sql":"select ''功能实现'' as c1, ''30%'' as c2, ''96.0'' as c3, ''28.8'' as c4 union all select ''性能与稳定性'', ''20%'', ''95.0'', ''19.0'' union all select ''安全与合规'', ''20%'', ''93.0'', ''18.6'' union all select ''文档与培训'', ''15%'', ''94.0'', ''14.1'' union all select ''项目管理与配合'', ''15%'', ''94.0'', ''14.1'' union all select ''合计'', ''100%'', ''—'', ''94.6''","single":false,"maxRows":100}',
   '{}', 160, 'ENABLED'),
  -- suggestions：4 行：段落块循环
  (@template_id, 'suggestions', '后续建议', 'SQL',
   '{"sql":"select ''1'' as seq_no, ''建立月度运行复盘机制，按季度发布小版本。'' as c1 union all select ''2'', ''打通人员异动信息的实时推送，消除 T+1 延迟。'' union all select ''3'', ''二期补充计量点位与碳核算模型。'' union all select ''4'', ''明确 P1 级事件 30 分钟到场、2 小时恢复。''","single":false,"maxRows":100}',
   '{}', 170, 'ENABLED'),
  -- glossary：6 行：表格行循环
  (@template_id, 'glossary', '术语与缩略语', 'SQL',
   '{"sql":"select ''主数据'' as c1, ''多系统共享、相对稳定的核心实体数据，此处指组织、人员、空间、设备四类。'' as c2 union all select ''工单闭环'', ''受理、派发、处置到归档的完整流程，任一环节缺失视为未闭环。'' union all select ''P1 级事件'', ''导致核心业务中断或数据丢失的生产事件。'' union all select ''95 分位响应时间'', ''响应时间升序排列后位于 95% 位置的取值，用于衡量长尾体验。'' union all select ''等保三级'', ''《网络安全等级保护基本要求》第三级。'' union all select ''T+1 延迟'', ''数据在业务发生后次日完成同步的时效口径。''","single":false,"maxRows":100}',
   '{}', 180, 'ENABLED'),
  -- ref_docs：6 行：表格行循环
  (@template_id, 'ref_docs', '相关文档', 'SQL',
   '{"sql":"select ''需求规格说明书'' as c1, ''XXX-SR-0001'' as c2, ''V1.2'' as c3, ''2026-01-18'' as c4 union all select ''概要设计说明书'', ''XXX-HLD-0001'', ''V1.1'', ''2026-02-26'' union all select ''数据库设计说明书'', ''XXX-DBD-0001'', ''V1.0'', ''2026-03-15'' union all select ''测试报告'', ''XXX-TR-0001'', ''V1.0'', ''2026-09-12'' union all select ''运维手册'', ''XXX-OM-0001'', ''V1.0'', ''2026-09-25'' union all select ''用户操作手册'', ''XXX-UM-0001'', ''V1.0'', ''2026-09-25''","single":false,"maxRows":100}',
   '{}', 190, 'ENABLED'),
  -- signers：5 行：表格行循环
  (@template_id, 'signers', '验收结论签署', 'SQL',
   '{"sql":"select ''验收组组长'' as c1, ''XXX'' as c2, ''XXX 集团信息中心'' as c3, '''' as c4, '''' as c5 union all select ''委托单位代表'', ''XXX'', ''XXX 集团信息中心'', '''', '''' union all select ''业务部门代表'', ''XXX'', ''XXX 集团运营管理部'', '''', '''' union all select ''承建单位代表'', ''XXX'', ''XXX 科技有限公司'', '''', '''' union all select ''监理单位代表'', ''XXX'', ''XXX 监理公司'', '''', ''''","single":false,"maxRows":100}',
   '{}', 200, 'ENABLED')
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
