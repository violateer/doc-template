package io.github.violateer.doctemplate.example;

import io.github.violateer.doctemplate.core.datasource.DocumentDataProvider;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class DemoReportProvider implements DocumentDataProvider {
    @Override public String getCode() { return "demoReport"; }
    @Override public Object execute(Map<String, Object> params) {
        return Map.of("reportNo", "DEMO-001", "title", "示例报表", "requestedId", params.getOrDefault("id", "未提供"));
    }
}
