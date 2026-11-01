package io.github.violateer.doctemplate.starter.auth;

import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateAutoConfiguration;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证装配。
 *
 * <p>模块只依赖自己的 {@link DocTemplateAuthorizer} 接口，不内置任何认证框架的适配器。
 * 宿主无论用什么方式识别身份（会话、Token、网关透传请求头、自建上下文），
 * 都只需在宿主侧写一个 Bean 桥接进来，Starter 因此不携带任何认证框架依赖。</p>
 */
@AutoConfiguration(after = DocTemplateAutoConfiguration.class)
@ConditionalOnProperty(prefix = "doc-template", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocTemplateProperties.class)
public class DocTemplateAuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocTemplateAuthAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public DocTemplateGuard docTemplateGuard(DocTemplateProperties properties,
                                             ObjectProvider<DocTemplateAuthorizer> authorizers) {
        DocTemplateProperties.Auth auth = properties.getAuth();
        AuthMode mode = auth.getMode() == null ? AuthMode.NONE : auth.getMode();
        DocTemplateAuthorizer authorizer;
        if (mode == AuthMode.CUSTOM) {
            authorizer = authorizers.getIfAvailable();
            if (authorizer == null) {
                throw new IllegalStateException(
                        "doc-template.auth.mode=custom，但容器里没有 DocTemplateAuthorizer Bean。"
                                + "请实现该接口并注册为 Spring Bean，或把 mode 改成 none");
            }
        } else {
            authorizer = new NoopDocTemplateAuthorizer(auth);
        }
        DocTemplateGuard guard = new DocTemplateGuard(authorizer, mode, auth.getAnonymousUser());
        log.debug("文档模板认证：mode={}，身份来源={}，操作人兜底={}",
                guard.mode(), guard.authorizerName(), auth.getAnonymousUser());
        if (guard.mode() == AuthMode.NONE) {
            log.warn("文档模板未启用认证：/api/doc-template/** 对所有调用方开放（需要登录态请实现 DocTemplateAuthorizer 并配置 doc-template.auth.mode=custom）");
        }
        return guard;
    }

    /** 统一在 API 前缀上校验登录态；NONE 模式下 requireLogin() 直接放行。 */
    @Bean
    public WebMvcConfigurer docTemplateAuthWebMvcConfigurer(DocTemplateGuard guard) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new DocTemplateAuthInterceptor(guard))
                        .addPathPatterns("/api/doc-template/**");
            }
        };
    }
}
