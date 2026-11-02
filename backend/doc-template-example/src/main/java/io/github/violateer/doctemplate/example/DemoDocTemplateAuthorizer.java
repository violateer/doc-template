package io.github.violateer.doctemplate.example;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.stp.StpUtil;
import io.github.violateer.doctemplate.starter.auth.DocTemplateAuthorizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 示例：宿主侧把已有的认证体系桥接给文档模板模块。
 *
 * <p>模块不内置 Sa-Token 适配器，宿主只需要这一个 Bean。这里用本模块自带的 Sa-Token 演示，
 * 换成 Spring Security、JWT 或网关透传的写法完全一样，区别只在取用户那一行。</p>
 *
 * <p>这个 Bean 一直存在，但只有在 {@code doc-template.auth.mode=custom} 时才会被启用：
 * {@code none} 模式下模块强制走无认证实现，即使容器里有 Bean 也不会调用它。
 * 切换模式只改配置，不需要动代码。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DemoDocTemplateAuthorizer {

    @Bean
    public DocTemplateAuthorizer docTemplateAuthorizer() {
        return () -> {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId == null) {
                // 页面导航（点链接、iframe 刷新、302 之后重新打开管理页）带不了 Authorization 头，
                // 只有 Cookie；而配了 token-prefix 时 sa-token 只认「Bearer xxx」这种提交值，
                // Cookie 里存的却是裸 token，于是被判成未登录——症状是「登录完又被弹回登录页」。
                // 这里拿裸值再校验一次，仍然走 sa-token 的会话校验，不是「有 Cookie 就放行」。
                String raw = SaHolder.getRequest().getCookieValue(StpUtil.getTokenName());
                if (raw != null && !raw.isBlank()) {
                    loginId = StpUtil.getLoginIdByToken(raw);
                }
            }
            return loginId == null ? null : String.valueOf(loginId);
        };
    }
}
