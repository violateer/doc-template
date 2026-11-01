package io.github.violateer.doctemplate.starter.auth;

/**
 * 宿主认证接入点（SPI）。
 *
 * <p>模块不预设任何认证框架，也不内置任何适配器：只要宿主能告诉模块「当前调用方是谁」，
 * 就能接进来。身份来源可以是会话、Token、网关透传请求头，或宿主自建的请求上下文 ——
 * 宿主侧实现这一个方法即可：</p>
 *
 * <pre>{@code
 * @Bean
 * DocTemplateAuthorizer docTemplateAuthorizer() {
 *     return () -> CurrentUserHolder.getUserId();   // 取不到返回 null
 * }
 * }</pre>
 *
 * <p>实现方不要抛异常，未认证时返回 {@code null} 即可，统一由 {@link DocTemplateGuard} 决定是否返回 401。</p>
 */
@FunctionalInterface
public interface DocTemplateAuthorizer {

    /**
     * @return 当前调用方标识（用户名 / 用户 ID / 租户内唯一标识）；未认证或识别不到时返回 {@code null}。
     */
    String currentUser();
}
