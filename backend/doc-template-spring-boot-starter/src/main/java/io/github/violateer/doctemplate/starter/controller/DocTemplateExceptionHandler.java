package io.github.violateer.doctemplate.starter.controller;

import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.auth.DocTemplateUnauthenticatedException;
import io.github.violateer.doctemplate.starter.service.DocTemplateSqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Returns readable JSON errors for the doc-template API so the embedded UI can show the real
 * cause instead of a generic 500 page. Only controllers under this package are affected.
 */
@RestControllerAdvice(basePackages = "io.github.violateer.doctemplate.starter.controller")
public class DocTemplateExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(DocTemplateExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return clientError(HttpStatus.BAD_REQUEST, error);
    }

    /** 需要认证但当前请求没有有效身份：让前端能区分「没登录」和「服务端炸了」。 */
    @ExceptionHandler(DocTemplateUnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthenticated(DocTemplateUnauthenticatedException error) {
        return clientError(HttpStatus.UNAUTHORIZED, error);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoSuchElementException error) {
        return clientError(HttpStatus.NOT_FOUND, error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleState(IllegalStateException error) {
        return clientError(HttpStatus.BAD_REQUEST, error);
    }

    /**
     * SQL 数据块执行失败。消息在抛出前已经脱敏，数据库原文只写日志，
     * 避免把表名、列名、SQL 片段回给调用方。
     */
    @ExceptionHandler(DocTemplateSqlException.class)
    public ResponseEntity<Map<String, Object>> handleSqlFailure(DocTemplateSqlException error) {
        log.warn("Doc template SQL failure", error);
        return body(HttpStatus.BAD_REQUEST, StrUtil.blankToDefault(error.getMessage(), "SQL 执行失败"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception error) {
        log.error("Doc template API failure", error);
        // 兜底分支不回传异常内部信息（文件路径、SQL、堆栈片段），只让使用者去看服务端日志
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请查看服务端日志");
    }

    /**
     * 4xx：参数写错、记录不存在这类问题属于调用方问题，堆栈没有价值，但**消息必须留一条**——
     * 否则前端只看到弹窗、控制台一片空白，连是哪个接口报的什么都查不到。
     * 需要堆栈时把 doc-template 的日志级别调到 debug。
     */
    private ResponseEntity<Map<String, Object>> clientError(HttpStatus status, Exception error) {
        String msg = message(error);
        log.warn("Doc template API {}：{}", status.value(), msg);
        if (log.isDebugEnabled()) log.debug("Doc template API {} 详细堆栈", status.value(), error);
        return body(status, msg);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", status.value());
        payload.put("msg", message);
        payload.put("success", false);
        return ResponseEntity.status(status).body(payload);
    }

    /**
     * 取模块自己写的异常消息。优先用最外层消息（都是本模块组装的），只有它为空时才退到根因，
     * 这样底层驱动/IO 异常的原始描述不会被顺带抛给调用方。
     */
    private static String message(Throwable error) {
        String text = StrUtil.blankToDefault(error.getMessage(), "");
        if (!text.isBlank()) return text;
        return StrUtil.blankToDefault(ExceptionUtil.getRootCauseMessage(error), error.getClass().getSimpleName());
    }
}
