package io.github.violateer.doctemplate.starter.auth;

/**
 * 文档模板模块的认证方式。
 *
 * <p>模块只做「当前调用方是谁」这一层识别，不做权限码校验。它不内置任何认证框架的适配器：
 * 宿主用什么方式识别身份，都通过 {@link DocTemplateAuthorizer} 桥接进来。</p>
 */
public enum AuthMode {
    /** 不启用认证：接口对所有调用方开放，操作人取配置的匿名值或透传请求头。 */
    NONE,
    /** 宿主自行实现 {@link DocTemplateAuthorizer} 并注册为 Bean；未认证返回 {@code null} 时接口 401。 */
    CUSTOM
}
