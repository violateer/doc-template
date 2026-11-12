package io.github.violateer.doctemplate.starter.autoconfigure;

import io.github.violateer.doctemplate.starter.auth.AuthMode;
import io.github.violateer.doctemplate.starter.auth.DocTemplateGuard;
import io.github.violateer.doctemplate.starter.ai.DocTemplateAiImportService;
import io.github.violateer.doctemplate.starter.service.DocTemplateFileStorage;
import io.github.violateer.doctemplate.starter.support.DocTemplateVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 启动横幅与配置摘要。
 *
 * <p>模块的配置散在数据源、存储、认证、数据块几处，各打一行日志既吵又难对照。这里等所有 Bean
 * 就绪后统一打一块：横幅 +「数据源 / 文件存储 / 认证 / 管理页 / 数据块 / 元数据表」，
 * 出问题时一眼能看出这个实例到底用了什么。</p>
 *
 * <p>放在 {@link SmartInitializingSingleton} 而不是各 Bean 的构造器里，是因为要打印「最终生效」的值：
 * 存储类型、认证模式、宿主数据源这些都是在 Bean 装配阶段才定下来的。</p>
 */
final class DocTemplateStartupReport implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateStartupReport.class);

    private static final String RULE = "=".repeat(72);

    /** ASCII 横幅（DOC）；右侧副标题用 %-26s 对齐，不手写空格。 */
    private static final String[] ART = {
            " ____   ___   ____",
            "|  _ \\ / _ \\ / ___|",
            "| | | | | | | |",
            "| |_| | |_| | |___",
            "|____/ \\___/ \\____|"
    };

    /** 摘要标签列的显示宽度（中文按 2 列算）。 */
    private static final int LABEL_WIDTH = 11;

    private final DocTemplateProperties properties;
    private final DocTemplateDataSourceResolver dataSourceResolver;
    private final DocTemplateFileStorage fileStorage;
    private final ObjectProvider<DocTemplateGuard> guardProvider;
    private final ObjectProvider<DocTemplateAiImportService> importService;
    private final ApplicationContext context;

    DocTemplateStartupReport(DocTemplateProperties properties,
                             DocTemplateDataSourceResolver dataSourceResolver,
                             DocTemplateFileStorage fileStorage,
                             ObjectProvider<DocTemplateGuard> guardProvider,
                             ObjectProvider<DocTemplateAiImportService> importService,
                             ApplicationContext context) {
        this.properties = properties;
        this.dataSourceResolver = dataSourceResolver;
        this.fileStorage = fileStorage;
        this.guardProvider = guardProvider;
        this.importService = importService;
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // 建表要先跑：摘要里的「元数据表」一行反映的是建表之后的状态
        DocTemplateSchemaInitializer.Status status =
                new DocTemplateSchemaInitializer(properties, dataSourceResolver).initialize();
        log.info("\n{}", String.join("\n", block(status)));
        // 表建好了才谈得上恢复：残留的 RUNNING 任务要标失败，过期的思维链要清掉
        DocTemplateAiImportService imports = importService.getIfAvailable();
        if (imports != null) imports.recover();
    }

    /** 整块拼成一个字符串一次打出去，避免被其他 Bean 的启动日志插进来切断。 */
    private List<String> block(DocTemplateSchemaInitializer.Status status) {
        List<String> lines = new ArrayList<>(16);
        lines.add(RULE);
        lines.addAll(banner());
        lines.add(RULE);
        lines.add(line("数据源", dataSourceLine(status)));
        lines.add(line("文件存储", fileStorage.describe()));
        lines.add(line("认证", authLine()));
        lines.add(line("管理页", adminUrl()));
        lines.add(line("数据块", dataBlockLine()));
        lines.add(line("元数据表", tableLine(status)));
        lines.add(RULE);
        return lines;
    }

    private List<String> banner() {
        String version = version();
        String[] side = {
                "doc-template · 文档模板引擎",
                "Spring Boot Starter" + (version == null ? "" : " " + version)
        };
        List<String> lines = new ArrayList<>(ART.length);
        for (int i = 0; i < ART.length; i++) {
            String text = i < side.length ? side[i] : "";
            lines.add(text.isEmpty() ? ART[i] : String.format("%-26s%s", ART[i], text));
        }
        return lines;
    }

    private String dataSourceLine(DocTemplateSchemaInitializer.Status status) {
        String label = dataSourceResolver.moduleSourceLabel();
        if (status.product() == null) return label;
        return label + " · " + status.product() + (status.version() == null ? "" : " " + status.version());
    }

    private String authLine() {
        DocTemplateGuard guard = guardProvider.getIfAvailable();
        if (guard == null) return "未装配";
        if (guard.mode() == AuthMode.NONE) return "none · 不校验登录态";
        String base = guard.mode().name().toLowerCase(Locale.ROOT) + " · 身份来源 " + guard.authorizerName();
        // 跳不跳登录页直接影响「打开管理页会看到什么」，值得出现在摘要里
        String loginPage = properties.getAuth().getLoginPage();
        return loginPage == null || loginPage.isBlank() ? base : base + " · 登录页 " + loginPage.trim();
    }

    /**
     * 管理页地址。
     *
     * <p>端口取 {@code server.port}，不用 {@code webServer.getPort()}：摘要是在 Bean 就绪、
     * 容器还没 start 时打印的，那会儿 Tomcat 连接器尚未绑定，{@code getPort()} 会给出默认的 8080。
     * 端口配成 0（随机）时只给路径，不打印一个假端口。</p>
     */
    private String adminUrl() {
        String port = context.getEnvironment().getProperty("server.port", "8080").trim();
        if ("0".equals(port)) return "/doc-template/（server.port=0，端口随机）";
        return "http://localhost:" + port + "/doc-template/";
    }

    private String dataBlockLine() {
        DocTemplateProperties.DataBlock cfg = properties.getDataBlock();
        if (!cfg.isEnabled()) return "关闭";
        return "启用 · SQL 取数" + (cfg.isSqlEnabled() ? "启用" : "关闭")
                + " · 默认 " + cfg.getDefaultMaxRows() + " 行 / " + cfg.getDefaultTimeoutMs() + "ms";
    }

    private static String tableLine(DocTemplateSchemaInitializer.Status status) {
        if (status.skipped()) return "未启用（doc-template.initialize-schema=false）";
        return status.created() ? "缺失，已自动创建" : "已存在";
    }

    private static String line(String label, String value) {
        return "  " + pad(label, LABEL_WIDTH) + value;
    }

    /** 按显示宽度补空格：中日韩字符按 2 列算，保证摘要的键值列对齐。 */
    private static String pad(String text, int columns) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return width >= columns ? text + " " : text + " ".repeat(columns - width);
    }

    /**
     * <p>版本号与「管理页左下角显示的版本」同一个来源，见 {@link DocTemplateVersion}。</p>
     */
    private static String version() {
        return DocTemplateVersion.current();
    }
}
