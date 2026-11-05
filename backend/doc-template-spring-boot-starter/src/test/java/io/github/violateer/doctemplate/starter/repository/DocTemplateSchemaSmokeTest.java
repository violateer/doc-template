package io.github.violateer.doctemplate.starter.repository;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateJdbcTemplate;
import io.github.violateer.doctemplate.starter.db.DocTemplateDialectProvider;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDataBlockEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDictEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDictItemEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateGenerateRecordEntity;
import io.github.violateer.doctemplate.starter.entity.DocTemplateVersionEntity;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 建表脚本 + 六个 Repository 的冒烟测试：每个配置好的数据库跑一遍。
 *
 * <p>覆盖建表、六张表的增删改查、分页（offset / limit）、关键字过滤（含纯数字的记录号）、
 * 自增主键回填。数据库从 {@link TestDatabases} 的环境变量读，没配就不跑。</p>
 */
class DocTemplateSchemaSmokeTest {

    @TestFactory
    Stream<DynamicTest> schemaRoundTrip() {
        return TestDatabases.configured().stream()
                .map(target -> DynamicTest.dynamicTest(target.label(), () -> roundTrip(target)));
    }

    private void roundTrip(TestDatabases.Target target) throws Exception {
        DataSource dataSource = target.dataSource();
        TestDatabases.dropTables(dataSource);
        try {
            new ResourceDatabasePopulator(new ClassPathResource(target.dialect().schemaScript())).execute(dataSource);
            exerciseTemplates(target, dataSource);
        } finally {
            TestDatabases.dropTables(dataSource);
        }
    }

    /** 六张表逐个走一遍：插入取回主键 → 查询 → 更新 → 删除。 */
    private void exerciseTemplates(TestDatabases.Target target, DataSource dataSource) {
        JdbcTemplate jdbc = new DocTemplateJdbcTemplate(dataSource);
        DocTemplateDialectProvider dialect = new DocTemplateDialectProvider(dataSource);
        assertEquals(target.dialect(), dialect.current(), "方言识别");

        DocTemplateRepository templates = new DocTemplateRepository(jdbc, dialect);
        DocTemplateVersionRepository versions = new DocTemplateVersionRepository(jdbc, dialect);
        DocTemplateDataBlockRepository blocks = new DocTemplateDataBlockRepository(jdbc, dialect);
        DocTemplateGenerateRecordRepository records = new DocTemplateGenerateRecordRepository(jdbc, dialect);
        DocTemplateDictRepository dicts = new DocTemplateDictRepository(jdbc, dialect);
        DocTemplateDictItemRepository items = new DocTemplateDictItemRepository(jdbc, dialect);

        // ---------------------------------------------------------------- doc_template
        DocTemplateEntity first = template("tpl-a", "验收单", "ENABLED");
        DocTemplateEntity second = template("tpl-b", "报价单", "DISABLED");
        DocTemplateEntity third = template("tpl-c", "验收报告", "ENABLED");
        templates.insert(first);
        templates.insert(second);
        templates.insert(third);

        assertNotNull(first.getId(), "插入后应回填自增主键");
        assertTrue(first.getId() > 0, "自增主键应为正数");
        assertTrue(third.getId() > second.getId(), "自增主键应递增");

        assertEquals("验收单", templates.findById(first.getId()).getTemplateName());
        assertEquals(first.getId(), templates.findByCode("tpl-a").getId());
        assertEquals(3L, templates.count(null, null));
        assertEquals(2L, templates.count(null, "ENABLED"));
        assertEquals(3L, templates.countAll());

        // 分页：按 id 倒序，offset / limit 生效
        List<DocTemplateEntity> page = templates.findPage(null, null, 0, 2);
        assertEquals(2, page.size(), "limit 应生效");
        assertEquals(third.getId(), page.get(0).getId(), "分页应按 id 倒序");
        assertEquals(second.getId(), page.get(1).getId());
        assertEquals(1, templates.findPage(null, null, 2, 2).size(), "offset 应生效");
        assertEquals(2, templates.findPage("验收", null, 0, 10).size(), "关键字应匹配模板名称");

        first.setTemplateName("验收单（改）");
        templates.update(first);
        assertEquals("验收单（改）", templates.findById(first.getId()).getTemplateName());

        // ---------------------------------------------------------------- doc_template_version
        DocTemplateVersionEntity v1 = version(first.getId(), 1);
        DocTemplateVersionEntity v2 = version(first.getId(), 2);
        versions.insert(v1);
        versions.insert(v2);
        assertNotNull(v1.getId());
        assertEquals(2, versions.findByTemplateId(first.getId()).size());
        assertEquals(2, versions.findLatest(first.getId()).getVersionNo().intValue(), "findLatest 应取最大版本号");

        // ---------------------------------------------------------------- doc_template_data_block
        DocTemplateDataBlockEntity block = new DocTemplateDataBlockEntity();
        block.setTemplateId(first.getId());
        block.setBlockCode("detail");
        block.setBlockName("明细");
        block.setDataSourceType("JAVA");
        block.setDataSourceConfig("{\"provider\":\"demo\"}");
        block.setSortOrder(0);
        block.setStatus("ENABLED");
        blocks.insert(block);
        assertNotNull(block.getId());
        assertEquals("明细", blocks.findById(block.getId()).getBlockName());
        assertEquals(1, blocks.findByTemplateId(first.getId()).size());
        assertEquals(block.getId(), blocks.findByTemplateIdAndCode(first.getId(), "detail").getId());

        // ---------------------------------------------------------------- doc_template_generate_record
        DocTemplateGenerateRecordEntity ok = record(first.getId(), "SUCCESS", "alice", null);
        DocTemplateGenerateRecordEntity failed = record(second.getId(), "FAILED", "bob", "渲染超时");
        DocTemplateGenerateRecordEntity third2 = record(third.getId(), "SUCCESS", "carol", null);
        records.insert(ok);
        records.insert(failed);
        records.insert(third2);
        assertNotNull(ok.getId());

        assertEquals(3, records.findPage(null, null, null, 0, 10).size());
        assertEquals(1, records.findPage("alice", null, null, 0, 10).size(), "关键字应匹配生成人");
        assertEquals(1, records.findPage("渲染超时", null, null, 0, 10).size(), "关键字应匹配失败说明");
        assertEquals(1, records.findPage(String.valueOf(failed.getId()), null, null, 0, 10).size(),
                "纯数字关键字应按记录号等值匹配");
        assertEquals(0, records.findPage("9" + failed.getId(), null, null, 0, 10).size(),
                "记录号是等值匹配，不做前缀模糊");
        assertEquals(1, records.findPage(null, "FAILED", null, 0, 10).size());
        assertEquals(2, records.findPage("验收", null, List.of(first.getId(), second.getId()), 0, 10).size(),
                "模板 id 集合应参与关键字匹配");
        assertEquals(3L, records.count(null, null, null));

        List<DocTemplateGenerateRecordEntity> recordPage = records.findPage(null, null, null, 1, 1);
        assertEquals(1, recordPage.size());
        assertEquals(failed.getId(), recordPage.get(0).getId(), "分页应按 id 倒序");

        // ---------------------------------------------------------------- 字典主子表
        DocTemplateDictEntity dict = new DocTemplateDictEntity();
        dict.setDictCode("gender");
        dict.setDictName("性别");
        dict.setRemark("演示用");
        dicts.insert(dict);
        assertNotNull(dict.getId());
        assertEquals("性别", dicts.findByCode("gender").getDictName());
        assertNull(dicts.findByCodeExcluding("gender", dict.getId()), "排除自身后编码不冲突");

        DocTemplateDictItemEntity male = dictItem(dict.getId(), "1", "男", 0);
        DocTemplateDictItemEntity female = dictItem(dict.getId(), "2", "女", 1);
        items.insert(male);
        items.insert(female);
        assertNotNull(male.getId());

        assertEquals(Map.of("1", "男", "2", "女"), items.labelsByCode("gender"));
        assertEquals(2L, items.countByDictId(dict.getId()));
        assertEquals(1, items.maxSortOrder(dict.getId()));
        assertNotNull(items.findByDictIdAndValue(dict.getId(), "1", null));
        assertNull(items.findByDictIdAndValue(dict.getId(), "1", male.getId()), "排除自身后值不冲突");

        assertEquals("1", items.findPage(dict.getId(), 0, 1).get(0).getItemValue(), "字典项分页按 sort_order");
        assertEquals("2", items.findPage(dict.getId(), 1, 1).get(0).getItemValue(), "字典项分页 offset 生效");

        List<DocTemplateDictEntity> dictList = dicts.findAll(null);
        assertEquals(1, dictList.size());
        assertEquals(2, dictList.get(0).getItemCount().intValue(), "列表应带出字典项数");
        assertEquals(1, dicts.findAll("性别").size(), "关键字应匹配字典名称");

        // ---------------------------------------------------------------- 删除
        records.deleteByTemplateId(first.getId());
        versions.deleteByTemplateId(first.getId());
        blocks.deleteByTemplateId(first.getId());
        templates.deleteById(first.getId());
        assertNull(templates.findById(first.getId()));
        assertTrue(records.findByTemplateId(first.getId()).isEmpty());
        assertTrue(versions.findByTemplateId(first.getId()).isEmpty());
        assertTrue(blocks.findByTemplateId(first.getId()).isEmpty());

        assertEquals(2, items.deleteByDictId(dict.getId()));
        dicts.deleteById(dict.getId());
        assertNull(dicts.findById(dict.getId()));

        assertFalse(templates.findAll().isEmpty());
    }

    private static DocTemplateEntity template(String code, String name, String status) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DocTemplateEntity e = new DocTemplateEntity();
        e.setTemplateCode(code);
        e.setTemplateName(name);
        e.setFileType("DOCX");
        e.setExportType("ORIGINAL");
        e.setStatus(status);
        e.setCreateTime(now);
        e.setUpdateTime(now);
        return e;
    }

    private static DocTemplateVersionEntity version(long templateId, int versionNo) {
        DocTemplateVersionEntity e = new DocTemplateVersionEntity();
        e.setTemplateId(templateId);
        e.setVersionNo(versionNo);
        e.setFileName("v" + versionNo + ".docx");
        e.setFileUrl("http://127.0.0.1/v" + versionNo + ".docx");
        e.setCreateTime(LocalDateTime.now().withNano(0));
        return e;
    }

    private static DocTemplateGenerateRecordEntity record(long templateId, String status, String createBy,
                                                          String errorMessage) {
        DocTemplateGenerateRecordEntity e = new DocTemplateGenerateRecordEntity();
        e.setTemplateId(templateId);
        e.setExportType("ORIGINAL");
        e.setStatus(status);
        e.setCreateBy(createBy);
        e.setErrorMessage(errorMessage);
        e.setCreateTime(LocalDateTime.now().withNano(0));
        return e;
    }

    private static DocTemplateDictItemEntity dictItem(long dictId, String value, String label, int sortOrder) {
        DocTemplateDictItemEntity e = new DocTemplateDictItemEntity();
        e.setDictId(dictId);
        e.setItemValue(value);
        e.setItemLabel(label);
        e.setSortOrder(sortOrder);
        return e;
    }
}
