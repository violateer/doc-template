package io.github.violateer.doctemplate.starter.auth;

/**
 * 认证门面：控制器只依赖它，不直接接触任何具体认证框架或身份来源。
 *
 * <p>{@link AuthMode#NONE} 下不强制登录，{@link #currentUser()} 退化为配置的匿名标识；
 * 其它模式下未认证的请求会在 {@link #requireLogin()} 处得到 401。</p>
 */
public class DocTemplateGuard {

    private final DocTemplateAuthorizer authorizer;
    private final AuthMode mode;
    private final String anonymousUser;

    public DocTemplateGuard(DocTemplateAuthorizer authorizer, AuthMode mode, String anonymousUser) {
        this.authorizer = authorizer;
        this.mode = mode == null ? AuthMode.NONE : mode;
        this.anonymousUser = (anonymousUser == null || anonymousUser.isBlank()) ? "anonymous" : anonymousUser;
    }

    /** 非 NONE 模式下校验登录态，未认证抛 {@link DocTemplateUnauthenticatedException}（401）。 */
    public void requireLogin() {
        if (mode == AuthMode.NONE) return;
        if (authorizer.currentUser() == null) {
            throw new DocTemplateUnauthenticatedException("未登录或登录已过期，无法访问文档模板接口");
        }
    }

    /**
     * 当前请求是否已有身份。{@link AuthMode#NONE} 下恒为 {@code true}——不启用认证就没有「未登录」。
     *
     * <p>与 {@link #requireLogin()} 的区别是不抛异常，供「未登录就跳登录页」这类分支判断使用。</p>
     */
    public boolean isAuthenticated() {
        return mode == AuthMode.NONE || authorizer.currentUser() != null;
    }

    /** 生成记录里记录的操作人；识别不到时用匿名标识兜底。 */
    public String currentUser() {
        String user = authorizer.currentUser();
        return (user == null || user.isBlank()) ? anonymousUser : user;
    }

    public AuthMode mode() {
        return mode;
    }

    public String authorizerName() {
        String name = authorizer.getClass().getSimpleName();
        // Lambda / 匿名实现拿不到可读的类名，退到接口名，避免日志里出现 $$Lambda/0x... 这种噪音
        if (name.isBlank() || name.contains("$$Lambda")) return DocTemplateAuthorizer.class.getSimpleName();
        return name;
    }
}
