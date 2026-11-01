package io.github.violateer.doctemplate.starter.auth;

/** 需要认证但当前请求没有有效身份，统一映射为 HTTP 401。 */
public class DocTemplateUnauthenticatedException extends RuntimeException {

    public DocTemplateUnauthenticatedException(String message) {
        super(message);
    }
}
