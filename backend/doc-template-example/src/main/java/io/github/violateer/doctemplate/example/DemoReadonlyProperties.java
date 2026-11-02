package io.github.violateer.doctemplate.example;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 演示站保护配置：把内置的示例模板标记成只读。
 *
 * <p>示例工程部署出去就是给人试用的站点，内置的那十个模板是讲解用的样板。
 * 被改乱、被删掉之后站点就没法演示了，所以按编码把它们锁起来：
 * 不能改元数据、不能传新版本、不能删版本、不能删模板，它们的数据块也不能增删改。
 * 使用者自己新建的模板不受任何影响。</p>
 *
 * <p>只在本示例宿主里生效，不属于 Starter 的能力——Starter 不认识「演示站」这个概念。</p>
 */
@ConfigurationProperties(prefix = "demo.readonly")
public class DemoReadonlyProperties {

    /** 演示站模式开关。关掉后所有模板都能正常增删改（本地调试用）。 */
    private boolean enabled = false;

    /** 受保护的模板编码，对应 {@code doc_template.template_code}。 */
    private Set<String> templateCodes = new LinkedHashSet<>();

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Set<String> getTemplateCodes() { return templateCodes; }

    public void setTemplateCodes(Set<String> templateCodes) {
        this.templateCodes = templateCodes == null ? new LinkedHashSet<>() : templateCodes;
    }
}
