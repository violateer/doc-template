package io.github.violateer.doctemplate.starter.support;

import java.io.InputStream;
import java.util.Properties;

/**
 * 当前正在运行的 Starter 版本号。
 *
 * <p>版本号取自构建期填充的 {@code META-INF/doc-template.properties}，与启动横幅用的是同一个来源，
 * 管理页左下角也读它，保证两处永远一致。</p>
 *
 * <p>不读 {@code META-INF/maven/<groupId>/<artifactId>/pom.properties}：那条路径把 groupId 写死了，
 * 换一次发布坐标就会静默丢掉版本。</p>
 *
 * <p><b>内部实现，不属于对外契约。</b></p>
 */
public final class DocTemplateVersion {

    private static final String RESOURCE = "/META-INF/doc-template.properties";

    /** {@code null} 表示读不到（资源过滤没跑到、资源被裁掉等）。 */
    private static volatile String cached;
    private static volatile boolean loaded;

    private DocTemplateVersion() {
    }

    /** 版本号（例如 {@code 0.2.1}）；读不到时返回 {@code null}，调用方自行决定怎么展示。 */
    public static String current() {
        if (!loaded) {
            synchronized (DocTemplateVersion.class) {
                if (!loaded) {
                    cached = read();
                    loaded = true;
                }
            }
        }
        return cached;
    }

    private static String read() {
        try (InputStream in = DocTemplateVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            Properties properties = new Properties();
            properties.load(in);
            String value = properties.getProperty("version");
            if (value == null) return null;
            value = value.trim();
            // 资源过滤没跑到时（例如直接跑 IDE 拷出来的 target/classes），这里读到的是原样的
            // ${project.version}。占位符不是版本号，按「读不到」处理：启动横幅和管理页宁可少显示
            // 一个版本，也不能把 ${...} 印出来。
            if (value.isEmpty() || value.contains("${")) return null;
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }
}
