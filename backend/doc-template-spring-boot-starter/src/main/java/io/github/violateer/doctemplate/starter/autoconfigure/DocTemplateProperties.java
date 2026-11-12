package io.github.violateer.doctemplate.starter.autoconfigure;

import io.github.violateer.doctemplate.starter.auth.AuthMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "doc-template")
public class DocTemplateProperties {
    private boolean enabled = true;
    private String basePath = "./data/doc-template";
    private boolean initializeSchema = true;
    /** PDF 导出用的 TrueType 字体文件；留空则从操作系统自动探测。 */
    private String pdfFont;
    /** 注册给 PDF 引擎的字体族名；留空则用字体文件名。 */
    private String pdfFontName;
    private final Auth auth = new Auth();
    private final Datasource datasource = new Datasource();
    private final Storage storage = new Storage();
    private final DataBlock dataBlock = new DataBlock();
    private final Ai ai = new Ai();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }
    public String getPdfFont() { return pdfFont; }
    public void setPdfFont(String pdfFont) { this.pdfFont = pdfFont; }
    public String getPdfFontName() { return pdfFontName; }
    public void setPdfFontName(String pdfFontName) { this.pdfFontName = pdfFontName; }
    public boolean isInitializeSchema() { return initializeSchema; }
    public void setInitializeSchema(boolean initializeSchema) { this.initializeSchema = initializeSchema; }
    public Auth getAuth() { return auth; }
    public Datasource getDatasource() { return datasource; }
    public Storage getStorage() { return storage; }
    public DataBlock getDataBlock() { return dataBlock; }
    public Ai getAi() { return ai; }

    /**
     * 让模型读文档结构、给出标记计划（S18 的第 ② 步）。
     *
     * <p><b>默认关闭</b>：开启意味着文档正文会被发到 {@code base-url} 指向的服务。
     * 只支持 OpenAI Chat Completions 兼容协议 —— 一个协议覆盖 DeepSeek / 通义 / 文心 / Kimi / Azure，
     * 不用为每家写适配器。宿主也可以自己注册一个 {@code DocTemplateAiClient} Bean 完全接管，
     * 那样这些配置项都不参与。</p>
     */
    public static class Ai {
        /** 关掉时 {@code /ai/plan} 直接报「AI 未启用」，其余标注能力不受影响。 */
        private boolean enabled = false;
        /** 接口地址，形如 {@code https://api.deepseek.com}（不带 /v1/chat/completions）。 */
        private String baseUrl;
        private String apiKey;
        private String model = "deepseek-chat";
        /**
         * 生成结构化产物建议 0：同样的输入要得到同样的计划。
         *
         * <p>设为负数表示不发送该字段 —— 部分推理模型（OpenAI o 系列这类）不接受
         * {@code temperature}，带上去会直接 400。</p>
         */
        private double temperature = 0;
        /**
         * 单次输出上限，<b>默认 32K，一次给足</b>。
         *
         * <p>推理模型的思考过程同样计入这个上限，给小了正文还没开始就被截断
         * （{@code finish_reason=length}、正文为空）。它只决定「模型能说多久」，不按用量计费 ——
         * 模型想完会自己停，不会真的花满。服务端自己的上限更小时（各家 8K / 16K / 32K / 64K 不等），
         * 会按它的报错自动降到能接受的值，不需要手动试。</p>
         *
         * <p>{@code 0} 表示不发送该参数，上限完全由服务端默认值决定 —— 通常偏小，不建议。</p>
         */
        private int maxTokens = 32768;
        /** 兼容接口的路径，默认 {@code /v1/chat/completions}。 */
        private String completionsPath = "/v1/chat/completions";
        /** 单次请求超时。推理模型的思考时间不可控，默认给到 2 分钟。 */
        private long timeoutMs = 120000;
        /**
         * 是否带 {@code response_format: {"type":"json_object"}}。
         * 个别服务商不认这个字段会直接 400，那种情况关掉即可（提示词里已经要求只输出 JSON）。
         */
        private boolean jsonMode = true;
        /** 结构快照的字符上限，超了会丢掉末尾的表格/段落并告警，避免一次请求把上下文撑爆。 */
        private int maxInputChars = 60000;
        /** 含首次在内的最大尝试次数：校验没过就把错误回灌给模型重来。 */
        private int maxAttempts = 3;
        /**
         * 原样并入请求体的额外字段，用来传各服务商自己的开关。
         *
         * <p>这是兜底手段，不是常规配置：模块本身按协议通用处理（不发送 {@code max_tokens}、
         * 兼容各家推理字段、400 时自动去掉服务端不认的字段），换模型通常不用动这里。
         * 只有在需要厂商专有能力时才填，例如关闭思考、调推理强度、指定 {@code chat_template_kwargs}，
         * 具体键名与取值以所用服务商的接口文档为准 —— 各家写法互不通用。</p>
         *
         * <p>{@code messages} 和 {@code stream} 不允许覆盖（会破坏响应解析），其余键原样合并，
         * 包括 {@code max_tokens}、{@code temperature}。</p>
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();
        /**
         * 是否把每次尝试的提示词、思考过程、回答原文落进 {@code doc_template_ai_trace}。
         *
         * <p><b>默认开启</b>，但提示词里是文档全文，可能含业务数据。文档涉敏时关掉即可：
         * 关掉只影响「详情页能不能看到思维链」，出计划本身不受影响。</p>
         */
        private boolean traceEnabled = true;
        /** 思维链保留天数，{@code 0} 表示不清理。启动时删一次过期行，不引定时任务。 */
        private int traceRetentionDays = 30;
        /**
         * 是否用流式响应（{@code stream: true}）调用模型。
         *
         * <p><b>默认开启</b>：推理模型先想几十秒再出正文，一次性返回意味着这段时间页面上什么都看不到。
         * 流式之后思考过程与正文边生成边落进思维链表，详情里能看到内容一行行长出来。</p>
         *
         * <p>服务端不认 {@code stream} 或 {@code stream_options} 时（网关透传不全的情况），
         * 会自动退回一次性返回，功能不受影响，只是没有过程可看。</p>
         */
        private boolean stream = true;
        /**
         * 异步导入任务的线程池。
         *
         * <p>字段名与 getter 不一致是因为 {@code import} 是 Java 关键字 —— Spring 按 getter 绑定，
         * 配置里写的仍然是 {@code doc-template.ai.import.threads}。</p>
         */
        private final Import importConfig = new Import();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public String getCompletionsPath() { return completionsPath; }
        public void setCompletionsPath(String completionsPath) { this.completionsPath = completionsPath; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public boolean isJsonMode() { return jsonMode; }
        public void setJsonMode(boolean jsonMode) { this.jsonMode = jsonMode; }
        public int getMaxInputChars() { return maxInputChars; }
        public void setMaxInputChars(int maxInputChars) { this.maxInputChars = maxInputChars; }
        public int getMaxAttempts() { return maxAttempts; }
        public Map<String, Object> getExtraBody() { return extraBody; }
        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody == null ? new LinkedHashMap<>() : extraBody;
        }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public boolean isTraceEnabled() { return traceEnabled; }
        public void setTraceEnabled(boolean traceEnabled) { this.traceEnabled = traceEnabled; }
        public int getTraceRetentionDays() { return traceRetentionDays; }
        public void setTraceRetentionDays(int traceRetentionDays) { this.traceRetentionDays = traceRetentionDays; }
        public boolean isStream() { return stream; }
        public void setStream(boolean stream) { this.stream = stream; }
        public Import getImport() { return importConfig; }

        /**
         * 导入任务的执行队列。
         *
         * <p>默认 1 线程：一次导入要跑几十秒到几分钟，并发几路对模型服务商没有好处，只会互相抢限流。
         * 队列有界，满了直接拒绝（任务保持 {@code PENDING}），而不是把内存堆起来。</p>
         */
        public static class Import {
            private int threads = 1;
            private int queueCapacity = 50;

            public int getThreads() { return threads; }
            public void setThreads(int threads) { this.threads = threads; }
            public int getQueueCapacity() { return queueCapacity; }
            public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        }
    }

    /**
     * 认证配置。模块只识别「当前调用方是谁」，不做权限码校验，所以这里只有身份来源和兜底操作人。
     */
    public static class Auth {
        /** none | custom，默认 none（不启用认证）。custom 需要宿主自己注册 DocTemplateAuthorizer Bean。 */
        private AuthMode mode = AuthMode.NONE;
        /** 识别不到操作人时写入生成记录的名称。 */
        private String anonymousUser = "anonymous";
        /** 可选：none 模式下从该请求头取操作人（仅当网关已完成鉴权时使用）。 */
        private String userHeader;
        /**
         * 未登录打开管理页时跳转的登录页，留空表示不跳转（默认）。
         *
         * <p>站内路径（{@code /demo/login.html}）和完整地址（{@code https://sso.example.com/login}）都支持，
         * 跳转时自动追加 {@code redirect=<当前地址>}。只在 {@code mode=custom} 下生效——
         * {@code none} 模式没有「未登录」这回事。</p>
         */
        private String loginPage;

        public AuthMode getMode() { return mode; }
        public void setMode(AuthMode mode) { this.mode = mode == null ? AuthMode.NONE : mode; }
        public String getAnonymousUser() { return anonymousUser; }
        public void setAnonymousUser(String anonymousUser) { this.anonymousUser = anonymousUser; }
        public String getUserHeader() { return userHeader; }
        public void setUserHeader(String userHeader) { this.userHeader = userHeader; }
        public String getLoginPage() { return loginPage; }
        public void setLoginPage(String loginPage) { this.loginPage = loginPage; }
    }

    /**
     * 数据块取数（SQL / API / Java Provider）的执行开关与安全边界。
     *
     * <p><b>SQL 数据源不推荐使用</b>：它把「一条 SQL 的执行权」交给了模板配置者，能读到该数据源账号可见的
     * 所有表。模块会做语句白名单校验、只读连接和错误脱敏，但这些都只是纵深防御，不是安全边界。
     * 对外系统建议把 {@link #isSqlEnabled()} 设为 {@code false}，改用 {@code JAVA} 数据源（Provider 里
     * 由业务代码决定查什么）。</p>
     */
    public static class DataBlock {
        /** 关闭后生成时不再执行任何数据块，只用请求里手填的参数。 */
        private boolean enabled = true;
        /**
         * 是否允许 SQL 数据源。默认开启以保持既有行为；<b>对外系统建议关闭</b>。
         * 关闭后保存 SQL 数据块和执行 SQL 数据块都会被拒绝，已存在的 SQL 数据块在生成时直接报错。
         */
        private boolean sqlEnabled = true;
        /** SQL 数据块未配置 maxRows 时的默认值。 */
        private int defaultMaxRows = 500;
        /** API 数据块未配置 timeoutMs 时的默认超时。 */
        private long defaultTimeoutMs = 5000;
        /** API 数据块允许配置的最大超时，避免线程被长时间占用。 */
        private long maxTimeoutMs = 15000;
        /** 允许访问的接口主机；为空表示不限制。支持精确匹配与 *.example.com 后缀匹配。 */
        private List<String> allowedHosts = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isSqlEnabled() { return sqlEnabled; }
        public void setSqlEnabled(boolean sqlEnabled) { this.sqlEnabled = sqlEnabled; }
        public int getDefaultMaxRows() { return defaultMaxRows; }
        public void setDefaultMaxRows(int defaultMaxRows) { this.defaultMaxRows = defaultMaxRows; }
        public long getDefaultTimeoutMs() { return defaultTimeoutMs; }
        public void setDefaultTimeoutMs(long defaultTimeoutMs) { this.defaultTimeoutMs = defaultTimeoutMs; }
        public long getMaxTimeoutMs() { return maxTimeoutMs; }
        public void setMaxTimeoutMs(long maxTimeoutMs) { this.maxTimeoutMs = maxTimeoutMs; }
        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts == null ? new ArrayList<>() : allowedHosts; }
    }

    public static class Datasource {
        /**
         * 可选的独立模板库。留空（默认）时完全复用宿主应用的 DataSource，模块自己不建连接池；
         * 只有模板元数据表需要落在另一个库时才填。
         */
        private String url;
        private String driverClassName;
        private String username;
        private String password;
        private int maximumPoolSize = 5;
        private long connectionTimeout = 30000;
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }

    public static class Storage {
        /**
         * local（默认）或 s3。不配置 storage 时按 local 处理，文件落到 base-path 目录，
         * 模块不依赖任何外部存储服务；要接对象存储才由宿主配置 s3 这一段。
         */
        private String type = "local";
        private String endpoint;
        private String region = "us-east-1";
        private String bucket;
        private String accessKey;
        private String secretKey;
        private boolean pathStyleAccess = true;
        private String prefix = "doc-template";
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
        public String getPrefix() { return prefix; }
        public void setPrefix(String prefix) { this.prefix = prefix; }
    }
}
