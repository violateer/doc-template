package io.github.violateer.doctemplate.starter.controller;

import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.auth.DocTemplateGuard;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 从 Starter 的 jar 里提供内嵌管理页。
 *
 * <p>配了 {@code doc-template.auth.login-page} 且当前未登录时，页面请求会被 302 到该地址；
 * 静态资源（js / css / 图片）不跳转，否则登录页自己的脚本都加载不出来。同时在返回的
 * {@code index.html} 里注入 {@code window.DOC_TEMPLATE_CONFIG.loginUrl}，让「页面已经打开、
 * 但会话中途失效」时前端也能跳登录页，而不是只弹一串 401。</p>
 */
@RestController
public class DocTemplatePageController {
    private static final String ROOT = "META-INF/resources/doc-template/";
    private static final String INDEX = "index.html";

    /**
     * 静态资源的后缀。这类请求缺失时不能回落到 {@code index.html}。
     *
     * <p>回落会让浏览器拿到 {@code text/html}，按 module script 校验时报
     * 「Expected a JavaScript-or-Wasm module script ... MIME type of "text/html"」，
     * 看起来像 MIME 或网关配置问题，实际是文件没有打进 jar。</p>
     */
    private static final Pattern ASSET_SUFFIX =
            Pattern.compile("\\.(js|mjs|css|map|png|jpe?g|gif|svg|ico|webp|woff2?|ttf|eot)$", Pattern.CASE_INSENSITIVE);

    private final DocTemplateGuard guard;
    private final DocTemplateProperties properties;

    public DocTemplatePageController(DocTemplateGuard guard, DocTemplateProperties properties) {
        this.guard = guard;
        this.properties = properties;
    }

    @GetMapping({"/doc-template", "/doc-template/", "/doc-template/{*path}"})
    public ResponseEntity<byte[]> page(@PathVariable(name = "path", required = false) String path,
                                       HttpServletRequest request) throws Exception {
        String relative = StrUtil.blankToDefault(StrUtil.removePrefix(path, "/"), "");
        if (StrUtil.isBlank(relative) || relative.endsWith("/")) relative += INDEX;
        if (relative.contains("..") || relative.startsWith("/")) return ResponseEntity.badRequest().build();
        ClassPathResource resource = new ClassPathResource(ROOT + relative);
        boolean exists = resource.exists() && resource.isReadable();
        if (!exists && ASSET_SUFFIX.matcher(relative).find()) {
            return ResponseEntity.notFound().build();
        }
        // 其余不存在的路径按 SPA 路由处理，回 index.html
        if (!exists) {
            resource = new ClassPathResource(ROOT + INDEX);
        }
        // 只有「要返回页面」的请求才考虑跳转：直接打开 /doc-template/、深链接、以及回落到 index.html 的路径
        boolean pageRequest = INDEX.equals(relative) || !exists;
        String loginPage = loginPage();
        if (pageRequest && loginPage != null && !guard.isAuthenticated()) {
            return redirect(loginPage, request);
        }
        // Spring 内置的类型推断，省去手写的扩展名 → MediaType 映射。
        // 用「实际返回的那个文件」推类型：深链接回落到 index.html 时，按请求路径推会得到
        // octet-stream，浏览器就变成下载而不是渲染。
        String served = exists ? relative : INDEX;
        MediaType mediaType = MediaTypeFactory.getMediaType(served).orElse(MediaType.APPLICATION_OCTET_STREAM);
        if ("text".equals(mediaType.getType())) mediaType = MediaType.parseMediaType(mediaType + ";charset=UTF-8");
        byte[] body;
        try (var input = resource.getInputStream()) {
            body = StreamUtils.copyToByteArray(input);
        }
        if (pageRequest && loginPage != null) {
            body = withLoginUrl(body, loginPage);
        }
        // 产物文件名固定（index.js / index.css），文件名不再承担缓存失效，
        // 因此要求每次请求都回源校验；否则宿主升级 Starter 后，浏览器仍会跑旧脚本。
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(mediaType)
                .body(body);
    }

    /** 配了才算「要跳转」；空串和纯空白都当没配。 */
    private String loginPage() {
        String page = properties.getAuth().getLoginPage();
        return StrUtil.isBlank(page) ? null : page.trim();
    }

    private static ResponseEntity<byte[]> redirect(String loginPage, HttpServletRequest request) {
        String target = request.getRequestURI();
        if (request.getQueryString() != null) {
            target = target + "?" + request.getQueryString();
        }
        String redirect = URLEncoder.encode(target, StandardCharsets.UTF_8);
        String location = loginPage.contains("?")
                ? loginPage + "&redirect=" + redirect
                : loginPage + "?redirect=" + redirect;
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    /**
     * 把登录页地址注入到 {@code window.DOC_TEMPLATE_CONFIG}，供前端在收到 401 时跳转。
     *
     * <p>注入在 {@code </head>} 之前；页面结构被改过、找不到 head 时退到 {@code <body}，
     * 两者都没有就原样返回——少一个跳转不该让整个页面打不开。</p>
     */
    private static byte[] withLoginUrl(byte[] html, String loginPage) {
        String text = new String(html, StandardCharsets.UTF_8);
        String snippet = "<script>window.DOC_TEMPLATE_CONFIG=Object.assign(window.DOC_TEMPLATE_CONFIG||{},{loginUrl:\""
                + loginPage.replace("\\", "\\\\").replace("\"", "\\\"") + "\"});</script>";
        int at = text.lastIndexOf("</head>");
        if (at < 0) at = text.lastIndexOf("<body");
        if (at < 0) return html;
        return (text.substring(0, at) + snippet + text.substring(at)).getBytes(StandardCharsets.UTF_8);
    }
}
