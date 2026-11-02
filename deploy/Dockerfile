# ============================================================================
# doc-template-example 的运行镜像（示例宿主，不是发布物）。
#
#   docker build -f deploy/Dockerfile -t doc-template-example:latest .
#
# 两段式：
#   1) build —— Maven + JDK 17 打出可执行 jar（-pl example -am，连带 core / starter）
#   2) runtime —— 只有 JRE、时区和中文字体，镜像小且能正确导出中文 PDF
#
# 前端不用在这里构建：`frontend/doc-template-ui` 的产物已经提交进
# `doc-template-spring-boot-starter/src/main/resources/META-INF/resources/doc-template/`。
#
# 刻意不用 BuildKit 的 cache mount：那需要先拉 `docker/dockerfile` 前端镜像，
# 内网 / 国内网络下多一个失败点。改成「先 COPY 各模块 pom 预下依赖」，
# pom 不变时这一层仍然命中缓存。
# ============================================================================

# ---------------------------------------------------------------------------
# 1) 构建阶段
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

# 国内加速用；留空则走 Maven Central
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public

WORKDIR /src/backend

RUN if [ -n "$MAVEN_MIRROR" ]; then \
      printf '%s\n' \
        '<settings>' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>build-mirror</id>' \
        '      <mirrorOf>*</mirrorOf>' \
        "      <url>$MAVEN_MIRROR</url>" \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /root/build-settings.xml; \
    fi

# 先只放 pom：依赖没变时下面的预下载层直接命中缓存
COPY backend/pom.xml ./
COPY backend/doc-template-core/pom.xml doc-template-core/pom.xml
COPY backend/doc-template-spring-boot-starter/pom.xml doc-template-spring-boot-starter/pom.xml
COPY backend/doc-template-example/pom.xml doc-template-example/pom.xml

# 预下载依赖与插件；失败不让整次构建挂掉（下面 package 会补齐缺的部分）
RUN set -eu; \
    if [ -f /root/build-settings.xml ]; then SETTINGS="-s /root/build-settings.xml"; else SETTINGS=""; fi; \
    mvn -B $SETTINGS -pl doc-template-example -am -DskipTests dependency:go-offline \
      || echo "依赖预下载未完全成功，交由 package 阶段补齐"

COPY backend/ /src/backend/

RUN set -eu; \
    if [ -f /root/build-settings.xml ]; then SETTINGS="-s /root/build-settings.xml"; else SETTINGS=""; fi; \
    mvn -B $SETTINGS -pl doc-template-example -am -DskipTests package

# ---------------------------------------------------------------------------
# 2) 运行阶段
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

# 可选：apt 镜像。只填主机名，**不要带 /ubuntu 路径**——
# 源地址里的 /ubuntu 是保留的，带路径会拼成 .../ubuntu/ubuntu 直接 404。
#   国内：https://mirrors.aliyun.com
#   留空：用镜像自带的 Ubuntu 官方源
ARG APT_MIRROR=

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"

# curl —— 容器健康检查 / 流水线探测
# fontconfig + wqy-microhei —— PDF 导出要中文字体，DocTemplatePdfRenderer 会探测
#   /usr/share/fonts/truetype/wqy/wqy-microhei.ttc，正好是这个包装的位置
RUN set -eu; \
    if [ -n "$APT_MIRROR" ]; then \
      for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do \
        if [ -f "$f" ]; then \
          sed -i -E "s#https?://(archive|security|ports)\.ubuntu\.com#$APT_MIRROR#g" "$f"; \
        fi; \
      done; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl tzdata fontconfig fonts-wqy-microhei; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /src/backend/doc-template-example/target/doc-template-example-*.jar /app/app.jar

# 本地磁盘存储的落点（没配 doc-template.storage 时用它），由 compose 挂成卷
RUN mkdir -p /app/data/doc-template

EXPOSE 9150

# 健康检查打内嵌管理页：静态资源不经过认证拦截器，200 就说明 Web 层和静态资源都正常
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
    CMD curl -fsS http://127.0.0.1:9150/doc-template/ >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
