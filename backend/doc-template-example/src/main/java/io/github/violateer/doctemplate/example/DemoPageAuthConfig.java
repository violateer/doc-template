package io.github.violateer.doctemplate.example;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 示例宿主的页面级登录拦截：只在 {@code doc-template.auth.mode=custom} 时生效。
 *
 * <p>模块本身只拦 API（{@code /api/doc-template/**}），静态页面永远放行——页面不含数据。
 * 这里额外加一层，是为了让 demo 的体验完整：未登录直接打开管理页会被送到登录页，登录后跳回来。
 * 真实宿主一般由前端路由守卫或网关做这件事，不需要照抄这段。</p>
 *
 * <p>只拦页面入口，不拦 {@code /doc-template/assets/**}，否则未登录时连 JS/CSS 都会被重定向。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "doc-template.auth", name = "mode", havingValue = "custom")
public class DemoPageAuthConfig implements WebMvcConfigurer {

    private static final String LOGIN_PAGE = "/demo/login.html";

    /** 与 sa-token.token-name 一致；只用来兜底判断「浏览器手里有没有会话」，不做鉴权。 */
    private static final String TOKEN_COOKIE = "Authorization";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws Exception {
                if (StpUtil.getLoginIdDefaultNull() != null) {
                    return true;
                }
                // 兜底：会话读不出来但 Cookie 还在时也放行页面，避免配置不一致导致无限重定向。
                // 页面本身不含数据，真正的鉴权在 /api/doc-template/** 上，未登录照样 401。
                if (hasTokenCookie(request)) {
                    return true;
                }
                String target = request.getRequestURI();
                if (request.getQueryString() != null) {
                    target = target + "?" + request.getQueryString();
                }
                response.sendRedirect(LOGIN_PAGE + "?redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8));
                return false;
            }
        }).addPathPatterns("/doc-template", "/doc-template/", "/doc-template/index.html");
    }

    private static boolean hasTokenCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (TOKEN_COOKIE.equals(cookie.getName())
                    && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
