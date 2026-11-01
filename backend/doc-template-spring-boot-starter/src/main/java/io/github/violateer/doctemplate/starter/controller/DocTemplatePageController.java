package io.github.violateer.doctemplate.starter.controller;

import cn.hutool.core.util.StrUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the embedded management UI from the starter jar.
 */
@RestController
public class DocTemplatePageController {
    private static final String ROOT = "META-INF/resources/doc-template/";

    @GetMapping({"/doc-template", "/doc-template/", "/doc-template/{*path}"})
    public ResponseEntity<byte[]> page(@PathVariable(name = "path", required = false) String path) throws Exception {
        String relative = StrUtil.blankToDefault(StrUtil.removePrefix(path, "/"), "");
        if (StrUtil.isBlank(relative) || relative.endsWith("/")) relative += "index.html";
        if (relative.contains("..") || relative.startsWith("/")) return ResponseEntity.badRequest().build();
        ClassPathResource resource = new ClassPathResource(ROOT + relative);
        if (!resource.exists() || !resource.isReadable()) {
            resource = new ClassPathResource(ROOT + "index.html");
        }
        // Spring 内置的类型推断，省去手写的扩展名 → MediaType 映射。
        MediaType mediaType = MediaTypeFactory.getMediaType(relative).orElse(MediaType.APPLICATION_OCTET_STREAM);
        if ("text".equals(mediaType.getType())) mediaType = MediaType.parseMediaType(mediaType + ";charset=UTF-8");
        try (var input = resource.getInputStream()) {
            return ResponseEntity.ok().contentType(mediaType).body(StreamUtils.copyToByteArray(input));
        }
    }
}
