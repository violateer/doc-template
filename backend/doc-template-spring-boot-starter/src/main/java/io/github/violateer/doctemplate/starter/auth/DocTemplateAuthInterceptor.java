package io.github.violateer.doctemplate.starter.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一在 {@code /api/doc-template/**} 上校验登录态。
 *
 * <p>静态管理页面（{@code /doc-template/**}）不拦截，API 才是需要保护的对象。
 * 抛出的 {@link DocTemplateUnauthenticatedException} 由模块的异常处理器转成 401。</p>
 */
public class DocTemplateAuthInterceptor implements HandlerInterceptor {

    private final DocTemplateGuard guard;

    public DocTemplateAuthInterceptor(DocTemplateGuard guard) {
        this.guard = guard;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod) {
            guard.requireLogin();
        }
        return true;
    }
}
