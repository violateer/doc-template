package io.github.violateer.doctemplate.starter.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import io.github.violateer.doctemplate.starter.autoconfigure.DocTemplateProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores uploaded template files locally or in an S3-compatible object store. */
public class DocTemplateFileStorage implements AutoCloseable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocTemplateFileStorage.class);

    private final DocTemplateProperties properties;
    private final S3Client s3;

    public DocTemplateFileStorage(DocTemplateProperties properties) {
        this.properties = properties;
        var cfg = properties.getStorage();
        if ("s3".equalsIgnoreCase(cfg.getType())) {
            if (StrUtil.hasBlank(cfg.getEndpoint(), cfg.getBucket(), cfg.getAccessKey(), cfg.getSecretKey())) {
                throw new IllegalStateException("doc-template.storage.type=s3 需要同时配置 endpoint、bucket、access-key、secret-key，"
                        + "请在宿主应用的配置里补齐，或改成 type=local 用本地磁盘");
            }
            var builder = S3Client.builder()
                .region(Region.of(StrUtil.blankToDefault(cfg.getRegion(), "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(cfg.isPathStyleAccess()).build())
                .endpointOverride(URI.create(cfg.getEndpoint()));
            this.s3 = builder.build();
            log.debug("文档模板文件存储：S3 兼容存储 endpoint={} bucket={} prefix={}",
                    cfg.getEndpoint(), cfg.getBucket(), prefix());
        } else if ("local".equalsIgnoreCase(cfg.getType())) {
            this.s3 = null;
            log.debug("文档模板文件存储：本地磁盘 {}（未配置 doc-template.storage 时的默认方式）", localRoot());
        } else {
            throw new IllegalStateException("Unsupported doc-template.storage.type: " + cfg.getType());
        }
    }

    /** 当前存储方式的一句话描述，供启动摘要使用。 */
    public String describe() {
        var cfg = properties.getStorage();
        if (s3 != null) {
            return "S3 兼容存储 " + cfg.getEndpoint() + " · bucket=" + cfg.getBucket() + " · prefix=" + prefix();
        }
        return "本地磁盘 " + localRoot() + "（默认）";
    }

    private String localRoot() {
        return Path.of(properties.getBasePath()).toAbsolutePath().normalize().toString();
    }

    public String store(long templateId, String originalName, byte[] bytes) throws Exception {
        String ext = StrUtil.equalsIgnoreCase("xlsx", FileUtil.extName(originalName)) ? ".xlsx" : ".docx";
        return writeObject(prefix() + "/templates/" + templateId + "/" + IdUtil.fastSimpleUUID() + ext, bytes);
    }

    /** Stores a rendered/generated document and returns its storage location. */
    public String storeGenerated(long templateId, String extension, byte[] bytes) throws Exception {
        String ext = StrUtil.isBlank(extension) ? ".docx" : StrUtil.addPrefixIfNot(extension, ".");
        return writeObject(prefix() + "/generated/" + templateId + "/" + IdUtil.fastSimpleUUID() + ext, bytes);
    }

    /** Writes an object to the configured storage backend, returning the persisted location. */
    public String writeObject(String key, byte[] bytes) throws Exception {
        if (s3 != null) {
            s3.putObject(PutObjectRequest.builder().bucket(properties.getStorage().getBucket()).key(key).build(), RequestBody.fromBytes(bytes));
            return "s3://" + properties.getStorage().getBucket() + "/" + key;
        }
        Path root = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Invalid file path");
        Files.createDirectories(target.getParent());
        Files.copy(new java.io.ByteArrayInputStream(bytes), target, StandardCopyOption.REPLACE_EXISTING);
        return root.relativize(target).toString().replace("\\", "/");
    }

    public byte[] read(String location) throws Exception {
        if (location != null && location.startsWith("s3://")) {
            String[] target = parseS3Location(location);
            ResponseBytes<GetObjectResponse> response =
                    s3.getObjectAsBytes(GetObjectRequest.builder().bucket(target[0]).key(target[1]).build());
            return response.asByteArray();
        }
        Path root = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
        Path stored = Path.of(location);
        Path path = (stored.isAbsolute() ? stored : root.resolve(stored)).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid file path");
        if (!Files.exists(path)) {
            // 本地找不到文件时给出可操作的说明：常见原因是这条记录当初写在别的存储上（或换了实例/工作目录）
            throw new IllegalStateException("模板文件在本地存储中不存在：" + location
                    + "（本地根目录 " + root + "）。若该文件原本存放在 S3 兼容存储上，请配置 doc-template.storage.type=s3 "
                    + "并补齐 endpoint、bucket、access-key、secret-key，或重新上传模板文件");
        }
        return Files.readAllBytes(path);
    }

    /**
     * 解析 {@code s3://bucket/key} 并校验当前实例确实启用了 S3 存储。
     *
     * <p>历史数据里的位置可能来自另一套存储配置（例如曾经用 S3 上传、现在按 local 启动），
     * 此时必须给出「配置不匹配」的明确提示，而不是让 S3 客户端以 null 收场。
     */
    private String[] parseS3Location(String location) {
        if (s3 == null) {
            throw new IllegalStateException("模板文件存放在 S3 兼容存储上（" + location
                    + "），但当前实例没有启用 S3 存储：请配置 doc-template.storage.type=s3 并补齐 endpoint、bucket、"
                    + "access-key、secret-key，或把模板文件重新上传到本地存储");
        }
        String value = location.substring(5);
        int slash = value.indexOf('/');
        if (slash < 1) throw new IllegalArgumentException("Invalid S3 object location: " + location);
        return new String[]{value.substring(0, slash), value.substring(slash + 1)};
    }

    /**
     * Removes the object backing a stored location. Deleting an already missing object is not an error,
     * so removing a template never fails just because the bucket entry was cleaned up manually.
     */
    public void delete(String location) throws Exception {
        if (StrUtil.isBlank(location)) return;
        if (location.startsWith("s3://")) {
            if (s3 == null) {
                // 存储配置与历史数据不一致时不阻断删除：否则这条记录永远删不掉，反而更难收拾。
                // 只提示对象可能仍留在原来的 bucket 里，由使用者自行清理。
                log.warn("跳过 S3 对象删除（当前实例未启用 S3 存储）：{}", location);
                return;
            }
            String[] target = parseS3Location(location);
            s3.deleteObject(DeleteObjectRequest.builder().bucket(target[0]).key(target[1]).build());
            return;
        }
        Path root = Path.of(properties.getBasePath()).toAbsolutePath().normalize();
        Path stored = Path.of(location);
        Path path = (stored.isAbsolute() ? stored : root.resolve(stored)).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid file path");
        Files.deleteIfExists(path);
        // Clean up the now-empty per-template directory when possible; ignore failures.
        try {
            Path parent = path.getParent();
            if (parent != null && parent.startsWith(root) && !parent.equals(root)) {
                try (var children = Files.list(parent)) {
                    if (children.findAny().isEmpty()) Files.deleteIfExists(parent);
                }
            }
        } catch (Exception ignored) {
            // Directory cleanup is best effort.
        }
    }

    public String fileName(String location) {
        return StrUtil.blankToDefault(FileUtil.getName(location), "template");
    }

    /** Normalises the configured object prefix (no leading or trailing slashes). */
    private String prefix() {
        String value = StrUtil.strip(StrUtil.blankToDefault(properties.getStorage().getPrefix(), "").trim(), "/", "/");
        return StrUtil.blankToDefault(value, "doc-template");
    }
    @Override public void close() { if (s3 != null) s3.close(); }
}
