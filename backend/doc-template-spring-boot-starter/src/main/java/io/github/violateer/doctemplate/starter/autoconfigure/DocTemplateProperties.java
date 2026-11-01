package io.github.violateer.doctemplate.starter.autoconfigure;

import io.github.violateer.doctemplate.starter.auth.AuthMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "doc-template")
public class DocTemplateProperties {
    private boolean enabled = true;
    private String basePath = "./data/doc-template";
    private boolean initializeSchema = true;
    /** Optional TrueType font used for PDF export; auto-detected from the OS when blank. */
    private String pdfFont;
    /** Family name reported to the PDF engine; defaults to the font file name. */
    private String pdfFontName;
    private final Auth auth = new Auth();
    private final Datasource datasource = new Datasource();
    private final Storage storage = new Storage();
    private final DataBlock dataBlock = new DataBlock();

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

        public AuthMode getMode() { return mode; }
        public void setMode(AuthMode mode) { this.mode = mode == null ? AuthMode.NONE : mode; }
        public String getAnonymousUser() { return anonymousUser; }
        public void setAnonymousUser(String anonymousUser) { this.anonymousUser = anonymousUser; }
        public String getUserHeader() { return userHeader; }
        public void setUserHeader(String userHeader) { this.userHeader = userHeader; }
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
