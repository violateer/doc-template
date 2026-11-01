package io.github.violateer.doctemplate.starter.auth;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 无认证模式：不校验登录态。
 *
 * <p>操作人优先取 {@code doc-template.auth.user-header} 指定的请求头（适合网关已完成鉴权的内网部署），
 * 取不到时退回配置的匿名标识。这个模式不会产生 401。</p>
 */
public class NoopDocTemplateAuthorizer implements DocTemplateAuthorizer {

    private final DocTemplateProperties.Auth auth;

    public NoopDocTemplateAuthorizer(DocTemplateProperties.Auth auth) {
        this.auth = auth;
    }

    @Override
    public String currentUser() {
        String header = auth.getUserHeader();
        if (header != null && !header.isBlank()) {
            String value = headerValue(header);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return auth.getAnonymousUser();
    }

    private static String headerValue(String name) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getHeader(name);
        }
        return null;
    }
}
