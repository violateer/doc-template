package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateAutoConfiguration.DocumentProviderRegistry;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateDataSourceResolver;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import io.github.violateer.doctemplate.starter.convert.DocTemplateConverterRegistry;
import io.github.violateer.doctemplate.starter.entity.DocTemplateDataBlockEntity;
import io.github.violateer.doctemplate.starter.support.DocTemplateColumnSpec;
import io.github.violateer.doctemplate.starter.support.DocTemplateJson;
import io.github.violateer.doctemplate.starter.support.DocTemplateRenderData;
import io.github.violateer.doctemplate.starter.support.DocTemplateSqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行模板下的数据块，把结果按 {@code blockCode} 收进渲染参数。
 *
 * <p>三种数据源：</p>
 * <ul>
 *   <li>{@code JAVA} —— 调用宿主应用注册的 {@code DocumentDataProvider}，<b>推荐</b>；</li>
 *   <li>{@code SQL} —— 用命名参数执行一条只读查询，支持多数据源（按 Bean 名选择）。语句要先过
 *       {@link DocTemplateSqlValidator}，执行时连接会被设为只读；</li>
 *   <li>{@code API} —— 请求一个 HTTP 接口，并按 {@code resultPath} 从响应里取节点。</li>
 * </ul>
 *
 * <p>参数映射（{@code param_config}）的值以 {@code $request.} 开头时从请求参数里取（支持
 * {@code a.b} 嵌套），其余按字面量常量使用。参数值一律以 JDBC 占位符绑定，不会拼进 SQL 文本。</p>
 */
public class DocTemplateDataBlockExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateDataBlockExecutor.class);

    /**
     * url 里的占位符：{@code {name}} 与 {@code ${name}} 等价，路径和查询串都能写，
     * 例如 {@code /orders/{id}?name={name}}。匹配前会把美元符号前缀归一掉。
     */
    private static final Pattern CURLY = Pattern.compile("\\{([A-Za-z0-9_.]+)}");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");
    /** 整个字符串就是一个占位符时，body 里按原始类型塞进去（对象、数组、数字、布尔都保留）。 */
    private static final Pattern DOLLAR_WHOLE = Pattern.compile("^\\$\\{([A-Za-z0-9_.]+)}$");
    /** API 数据块只开放 GET / POST 两种取数方式（GET 参数在 URL，POST 参数在请求体），都用于查询；PUT / PATCH / DELETE 不开放。 */
    private static final Set<String> METHODS = Set.of("GET", "POST");
    /** SQL 数据块的查询超时（秒），避免一条慢查询长期占住连接。 */
    private static final int QUERY_TIMEOUT_SECONDS = 30;

    private final DocumentProviderRegistry providers;
    private final DocTemplateProperties props;
    private final DocTemplateDataSourceResolver dataSourceResolver;
    private final DocTemplateConverterRegistry converters;

    public DocTemplateDataBlockExecutor(DocumentProviderRegistry providers, DocTemplateProperties props,
                                        DocTemplateDataSourceResolver dataSourceResolver) {
        this(providers, props, dataSourceResolver, new DocTemplateConverterRegistry());
    }

    public DocTemplateDataBlockExecutor(DocumentProviderRegistry providers, DocTemplateProperties props,
                                        DocTemplateDataSourceResolver dataSourceResolver,
                                        DocTemplateConverterRegistry converters) {
        this.providers = providers;
        this.props = props;
        this.dataSourceResolver = dataSourceResolver;
        this.converters = converters == null ? new DocTemplateConverterRegistry() : converters;
    }

    /**
     * 依次执行启用状态的数据块，返回「行数据 + 表格动态列的列定义」。
     * 任一数据块失败都会抛出异常（消息里带上数据块名称），让生成记录能定位到具体配置。
     */
    public DocTemplateRenderData collect(List<DocTemplateDataBlockEntity> blocks, Map<String, Object> requestParams) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, List<DocTemplateColumnSpec>> columns = new LinkedHashMap<>();
        if (!props.getDataBlock().isEnabled() || blocks == null || blocks.isEmpty()) {
            return new DocTemplateRenderData(data, columns);
        }
        Map<String, Object> request = requestParams == null ? Map.of() : requestParams;
        for (DocTemplateDataBlockEntity block : blocks) {
            if (block == null || !"ENABLED".equalsIgnoreCase(StrUtil.blankToDefault(block.getStatus(), "ENABLED"))) continue;
            String code = StrUtil.blankToDefault(block.getBlockCode(), "");
            if (code.isBlank()) continue;
            try {
                data.put(code, execute(block, request));
                List<DocTemplateColumnSpec> specs = columnsOf(block, request);
                if (!specs.isEmpty()) columns.put(code, specs);
            } catch (Exception error) {
                // SQL 失败的消息已经脱敏过，不要再用 rootCause 把数据库原文捞出来
                String detail = error instanceof DocTemplateSqlException
                        ? error.getMessage()
                        : ExceptionUtil.getRootCauseMessage(error);
                throw new IllegalStateException("数据块「" + StrUtil.blankToDefault(block.getBlockName(), code)
                        + "」执行失败：" + detail, error);
            }
        }
        return new DocTemplateRenderData(data, columns);
    }

    /**
     * 执行单个数据块，并按 {@code data_source_config.converters} 做字段值转换。
     *
     * <p>转换放在取数之后统一做，三种数据源（JAVA / SQL / API）行为一致；
     * 结果里的嵌套对象和数组会递归处理，明细行里的字段同样生效。</p>
     */
    public Object execute(DocTemplateDataBlockEntity block, Map<String, Object> requestParams) {
        Map<String, Object> config = config(block);
        Object raw = fetch(block, requestParams, config, typeOf(block.getDataSourceType()));
        return converters.apply(raw, config.get("converters"));
    }

    /**
     * 表格动态列的列定义：{@code data_source_config.columns} 自己配一份取数方式
     * （{@code type} 缺省沿用数据块的类型），取回来的数组按 {@link DocTemplateColumnSpec} 解析。
     *
     * <p>列源和主数据源共用块级 {@code param_config}，不另开一份参数映射；
     * {@code single} 对列源没有意义，这里强制按数组取。</p>
     *
     * @return 没配 {@code columns} 时返回空列表
     */
    public List<DocTemplateColumnSpec> columnsOf(DocTemplateDataBlockEntity block, Map<String, Object> requestParams) {
        Map<String, Object> config = config(block);
        Object raw = config.get("columns");
        if (raw == null) return List.of();
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("columns 配置必须是一个对象（含 type / sql / provider 等键），实际是 "
                    + raw.getClass().getSimpleName());
        }
        Map<String, Object> columnConfig = new LinkedHashMap<>();
        map.forEach((key, value) -> columnConfig.put(String.valueOf(key), value));
        String type = typeOf(StrUtil.blankToDefault(Convert.toStr(columnConfig.get("type")), block.getDataSourceType()));
        // 列源永远是数组：单条返回会变成 Map，解析不出来
        columnConfig.put("single", false);
        Object result = fetch(block, requestParams, columnConfig, type);
        return DocTemplateColumnSpec.parse(result);
    }

    private String typeOf(String raw) {
        return StrUtil.blankToDefault(raw, "JAVA").trim().toUpperCase(Locale.ROOT);
    }

    /** 按 type 取数，主数据源与列源共用这一份实现（含 SQL 的只读连接、脱敏、只读校验）。 */
    private Object fetch(DocTemplateDataBlockEntity block, Map<String, Object> requestParams,
                         Map<String, Object> config, String type) {
        return switch (type) {
            case "JAVA" -> viaProvider(block, requestParams, config);
            case "SQL" -> viaSql(block, requestParams, config);
            case "API" -> viaApi(block, requestParams, config);
            default -> throw new IllegalArgumentException("不支持的数据源类型：" + type);
        };
    }

    // ------------------------------------------------------------------ JAVA

    private Object viaProvider(DocTemplateDataBlockEntity block, Map<String, Object> requestParams,
                               Map<String, Object> config) {
        String provider = Convert.toStr(config.get("provider"), "").trim();
        if (provider.isEmpty()) throw new IllegalArgumentException("Java Provider 数据源缺少 provider 配置");
        return providers.execute(provider, params(block, requestParams));
    }

    // ------------------------------------------------------------------ SQL

    private Object viaSql(DocTemplateDataBlockEntity block, Map<String, Object> requestParams,
                          Map<String, Object> config) {
        if (!props.getDataBlock().isSqlEnabled()) {
            throw new IllegalArgumentException("SQL 数据源已被禁用（doc-template.data-block.sql-enabled=false），"
                    + "请改用 Java Provider 或 API 数据源");
        }
        // 语句类型白名单 + 关键字检查，任何不通过都会在执行前抛出
        String sql = DocTemplateSqlValidator.validate(Convert.toStr(config.get("sql"), ""));
        int maxRows = Math.max(1, Convert.toInt(config.get("maxRows"), props.getDataBlock().getDefaultMaxRows()));
        boolean single = Convert.toBool(config.get("single"), false);

        String sourceName = StrUtil.blankToDefault(Convert.toStr(config.get("dataSource")), "primary");
        DataSource chosen = dataSourceResolver.resolve(Convert.toStr(config.get("dataSource")));
        Map<String, Object> named = params(block, requestParams);
        List<Map<String, Object>> rows;
        try {
            rows = query(chosen, sql, named, maxRows);
        } catch (DataAccessException first) {
            // 没显式指定库时，业务表可能在模板库而不是宿主主库，自动再试一次，省得用户来回改配置
            DataSource module = dataSourceResolver.moduleDataSource();
            if (module == null || module == chosen) {
                log.warn("数据块「{}」SQL 执行失败（dataSource={}）", block.getBlockCode(), sourceName, first);
                throw new DocTemplateSqlException(sqlFailureMessage(sourceName), first);
            }
            try {
                rows = query(module, sql, named, maxRows);
                log.warn("数据块「{}」在 dataSource={} 执行失败，已自动回退到模板库", block.getBlockCode(), sourceName, first);
            } catch (DataAccessException second) {
                log.warn("数据块「{}」SQL 在宿主库与模板库均执行失败", block.getBlockCode(), second);
                throw new DocTemplateSqlException(sqlFailureMessage(sourceName) + "；在模板库重试也失败", second);
            }
        }
        if (!single) return rows;
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /** 对外只给这句话，数据库原文只进日志。 */
    private static String sqlFailureMessage(String sourceName) {
        return "SQL 执行失败（dataSource=" + sourceName + "），请检查 SQL 与数据源配置；详细原因见服务端日志";
    }

    /**
     * 执行一条只读查询。
     *
     * <p>参数值一律走 JDBC 占位符绑定，绝不拼进 SQL 文本；连接在执行前被设为只读
     * （{@code setReadOnly(true)}），执行完恢复原状态再归还连接池。</p>
     *
     * <p>只读是纵深防御：MySQL / PostgreSQL 会把会话置为只读事务，能拦住绕过校验的写操作；
     * 也有驱动只把 {@code setReadOnly} 当提示、并不真的生效，所以不能把它当唯一防线。</p>
     */
    private List<Map<String, Object>> query(DataSource source, String sql, Map<String, Object> params, int maxRows) {
        MapSqlParameterSource values = new MapSqlParameterSource(params);
        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);
        String jdbcSql = NamedParameterUtils.substituteNamedParameters(parsed, values);
        Object[] args = NamedParameterUtils.buildValueArray(parsed, values, null);

        try (Connection connection = source.getConnection()) {
            boolean originalReadOnly = false;
            try {
                originalReadOnly = connection.isReadOnly();
                if (!originalReadOnly) connection.setReadOnly(true);
            } catch (Exception unsupported) {
                log.debug("数据源不支持 setReadOnly，本次查询未启用只读连接：{}", unsupported.getMessage());
            }
            try {
                // suppressClose=true：连接归外层 try-with-resources 管，JdbcTemplate 不能把它关掉
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.setMaxRows(maxRows);
                jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                return jdbc.query(jdbcSql, new ColumnMapRowMapper(), args);
            } finally {
                if (!originalReadOnly) {
                    try {
                        connection.setReadOnly(false);
                    } catch (Exception ignored) {
                        // 归还前尽力恢复，失败不影响本次结果
                    }
                }
            }
        } catch (SQLException connectionError) {
            throw new IllegalStateException("获取或归还数据库连接失败", connectionError);
        }
    }

    // ------------------------------------------------------------------ API

    private Object viaApi(DocTemplateDataBlockEntity block, Map<String, Object> requestParams,
                          Map<String, Object> config) {
        String url = Convert.toStr(config.get("url"), "").trim();
        if (url.isEmpty()) throw new IllegalArgumentException("API 数据源缺少 url 配置");
        Map<String, Object> params = params(block, requestParams);

        String method = StrUtil.blankToDefault(Convert.toStr(config.get("method")), "GET").trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("不支持的请求方法：" + method + "（只支持 GET / POST）");

        // url 里 {name} 和 ${name} 两种写法都认：前者常用于路径和查询串，后者与 header / body 写法保持一致。
        // 占位符交给 Spring 的 URI 模板展开，编码只做一次（自己先 URLEncoder 再 encode() 会把 % 变成 %25）。
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url.replace("${", "{"));
        Map<String, Object> query = asMap(config.get("query"));
        query.forEach((key, value) -> builder.queryParam(key, replaceDollar(Convert.toStr(value, ""), params)));
        URI uri;
        try {
            uri = builder.build().expand(urlVariables(url, params)).encode().toUri();
        } catch (IllegalArgumentException unresolved) {
            throw new IllegalArgumentException("url 里的占位符没有对应参数：" + url, unresolved);
        }
        checkUri(uri);

        long timeout = timeout(config);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout);
        factory.setReadTimeout((int) timeout);
        RestClient client = RestClient.builder().requestFactory(factory).build();

        RestClient.RequestBodySpec spec = client.method(HttpMethod.valueOf(method)).uri(uri);
        Map<String, Object> headers = asMap(config.get("headers"));
        if (!headers.isEmpty()) {
            spec.headers(target -> headers.forEach((key, value) ->
                    target.set(key, replaceDollar(Convert.toStr(value, ""), params))));
        }
        boolean hasBody = config.get("body") != null && !"GET".equals(method);
        if (hasBody) {
            spec.contentType(MediaType.APPLICATION_JSON).body(replaceDeep(config.get("body"), params));
        }
        String text = spec.retrieve()
                .onStatus(status -> status.isError(), (request, response) -> {
                    throw new IllegalStateException("接口返回 HTTP " + response.getStatusCode().value() + "：" + errorBody(response));
                })
                .body(String.class);

        Object payload = StrUtil.isBlank(text) ? new LinkedHashMap<>() : DocTemplateJson.read(text);
        Object value = at(payload, Convert.toStr(config.get("resultPath"), ""));
        if (value == null) {
            throw new IllegalArgumentException("响应里没有 resultPath 指定的节点：" + Convert.toStr(config.get("resultPath"), ""));
        }
        return value;
    }

    private long timeout(Map<String, Object> config) {
        long configured = Convert.toLong(config.get("timeoutMs"), props.getDataBlock().getDefaultTimeoutMs());
        long max = Math.max(1000, props.getDataBlock().getMaxTimeoutMs());
        return Math.min(Math.max(1000, configured), max);
    }

    private void checkUri(URI uri) {
        String scheme = StrUtil.blankToDefault(uri.getScheme(), "").toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("API 数据源只支持 http / https：" + uri);
        }
        String host = StrUtil.blankToDefault(uri.getHost(), "").toLowerCase(Locale.ROOT);
        if (host.isEmpty()) throw new IllegalArgumentException("API 数据源 url 缺少主机名：" + uri);
        List<String> allowed = props.getDataBlock().getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) return;
        boolean matched = allowed.stream().anyMatch(rule -> {
            String target = StrUtil.blankToDefault(rule, "").trim().toLowerCase(Locale.ROOT);
            if (target.isEmpty()) return false;
            if (target.startsWith("*.")) {
                String suffix = target.substring(1);
                return host.endsWith(suffix) || host.equals(target.substring(2));
            }
            return host.equals(target);
        });
        if (!matched) {
            throw new IllegalArgumentException("API 数据块不允许访问主机 " + host
                    + "，请把主机名加入 doc-template.data-block.allowed-hosts");
        }
    }

    private String errorBody(ClientHttpResponse response) {
        try (InputStream body = response.getBody()) {
            String text = StrUtil.blankToDefault(new String(body.readAllBytes(), StandardCharsets.UTF_8), "");
            return StrUtil.maxLength(text.replaceAll("\\s+", " ").trim(), 300);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 按 {@code a.b.c} 路径取值，数组下标用数字段表示，例如 {@code data.rows.0.name}。 */
    private Object at(Object node, String path) {
        if (StrUtil.isBlank(path)) return node;
        Object current = node;
        for (String part : path.trim().split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                int index = Convert.toInt(part, -1);
                if (index < 0 || index >= list.size()) return null;
                current = list.get(index);
            } else {
                return null;
            }
            if (current == null) return null;
        }
        return current;
    }

    // ------------------------------------------------------------------ 参数

    /** 解析参数映射：{@code $request.xxx} 取请求参数（支持嵌套），其他值按常量处理。 */
    private Map<String, Object> params(DocTemplateDataBlockEntity block, Map<String, Object> requestParams) {
        Map<String, Object> mapping = DocTemplateJson.readMap(block.getParamConfig());
        Map<String, Object> request = requestParams == null ? Map.of() : requestParams;
        Map<String, Object> result = new LinkedHashMap<>();
        mapping.forEach((key, raw) -> {
            if (raw instanceof String text) {
                String trimmed = text.trim();
                if (trimmed.startsWith("$request.")) {
                    result.put(key, at(request, trimmed.substring("$request.".length())));
                } else if ("$request".equals(trimmed)) {
                    result.put(key, request);
                } else {
                    result.put(key, text);
                }
                return;
            }
            result.put(key, raw);
        });
        return result;
    }

    private Map<String, Object> config(DocTemplateDataBlockEntity block) {
        return DocTemplateJson.readMap(block.getDataSourceConfig());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        return Map.of();
    }

    /**
     * 取出 url 里所有占位符对应的参数值，交给 {@code UriComponents.expand()} 去做替换和编码。
     *
     * <p>不在字符串上直接拼值：值里带 {@code / ? & #} 时会被当成 URL 结构的一部分；
     * 自己编码又会被随后的 {@code encode()} 二次编码（{@code %} 变 {@code %25}，中文全废）。
     * 交给模板展开，编码只发生一次，路径段和查询串各按自己的规则转义。</p>
     */
    private Map<String, Object> urlVariables(String url, Map<String, Object> params) {
        Map<String, Object> vars = new LinkedHashMap<>();
        Matcher matcher = CURLY.matcher(url.replace("${", "{"));
        while (matcher.find()) {
            String name = matcher.group(1);
            if (vars.containsKey(name)) continue;
            Object value = at(params, name);
            if (value == null) throw new IllegalArgumentException("url 占位符没有对应参数：" + name);
            vars.put(name, value);
        }
        return vars;
    }

    /** 替换 {@code ${name}}，用于 header / query / body 里的字符串值。 */
    private String replaceDollar(String text, Map<String, Object> params) {
        if (text == null || text.indexOf('$') < 0) return text;
        Matcher matcher = DOLLAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = at(params, matcher.group(1));
            if (value == null) throw new IllegalArgumentException("占位符没有对应参数：" + matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(Convert.toStr(value, "")));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Object replaceDeep(Object node, Map<String, Object> params) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((key, value) -> copy.put(key, replaceDeep(value, params)));
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(replaceDeep(item, params));
            return copy;
        }
        if (node instanceof String text) {
            // 整个值就是一个占位符时保留原始类型：body 里可以直接塞对象、数组、数字、布尔，
            // 不会被 Convert.toStr 变成 "[a, b]" 这种字符串
            Matcher whole = DOLLAR_WHOLE.matcher(text.trim());
            if (whole.matches()) {
                Object value = at(params, whole.group(1));
                if (value == null) throw new IllegalArgumentException("占位符没有对应参数：" + whole.group(1));
                return value;
            }
            return replaceDollar(text, params);
        }
        return node;
    }
}
