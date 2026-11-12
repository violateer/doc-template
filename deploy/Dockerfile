# ============================================================================
# doc-template-example 的运行镜像（示例宿主，不是发布物）。
#
#   docker build -f deploy/Dockerfile -t doc-template-example:latest .
#
# 两段式：
#   1) build —— Maven + JDK 17 打出可执行 jar
#   2) runtime —— 只有 JRE、时区和中文字体，镜像小且能正确导出中文 PDF
#
# **Starter 从哪来，由 DOC_TEMPLATE_VERSION 决定**（见下面 ARG 的注释）：
#   留空      → 用仓库里的源码，`-am` 连带构建 core / starter（本地调试用）
#   填版本号  → 去中央仓库拉那个**正式版本**，不连带构建（演示站必须用这种）
#
# 前端不用在这里构建：`frontend/doc-template-ui` 的产物已经提交进
# `doc-template-spring-boot-starter/src/main/resources/META-INF/resources/doc-template/`。
# 所以「填版本号」时前端也一并来自 Central 的那个 jar，仓库里改的前端不会生效——
# 这正是演示站想要的效果：它验证的就是用户拿到的那份包。
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

# Starter 版本：
#   留空          = 用仓库源码（版本是父 POM 的 SNAPSHOT，构建时连带 core / starter）
#   0.1.1 这样的  = 从中央仓库拉这个已发布的正式版本
# **必须是已发布的正式版本**，不要填 -SNAPSHOT；填了没发布过的版本会解析失败。
ARG DOC_TEMPLATE_VERSION=

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

# 预下载依赖与插件，只为让下面那层命中缓存（pom 没变时省一次全量下载）。
#
# **这一步允许失败**，而且是故意允许的：阿里云是拉取式代理，某个新发布的文件第一次被请求时
# 会先返回 404、同时回源去中央仓库抓，抓完下一次请求才 200。刚发完版就构建演示站，
# 这里撞 404 是正常的，不能因此判定整次构建失败。
# 真正的解析在下一步的 package 里做，那里带 -U，会忽略这里留下的「找不到」负缓存。
RUN set -eu; \
    if [ -f /root/build-settings.xml ]; then SETTINGS="-s /root/build-settings.xml"; else SETTINGS=""; fi; \
    if [ -n "$DOC_TEMPLATE_VERSION" ]; then \
      mvn -B $SETTINGS -pl doc-template-example -Ddoc-template.version="$DOC_TEMPLATE_VERSION" \
          -DskipTests dependency:go-offline; \
    else \
      mvn -B $SETTINGS -pl doc-template-example -am -DskipTests dependency:go-offline; \
    fi \
      || echo "依赖预下载未完全成功（新版本首次构建时常见），由下面带 -U 的 package 阶段补齐"

COPY backend/ /src/backend/

# ---------------------------------------------------------------------------
# 内嵌管理页产物自检
#
# index.html 引用的每个资源（assets/index.js、assets/index.css 等）都必须真的在
# 产物目录里。文件名已经不带内容哈希，但产物仍然可能不完整：构建中途失败只写了
# 一部分、合并分支时只取了其中一部分、或者被忽略规则误伤。缺文件时镜像能启动、
# 健康检查也能通过（只探测 /doc-template/），要打开页面才暴露：白屏，控制台报
# 资源 404（管理页的页面控制器会把缺失的静态资源如实返回 404）。
# 所以在这里就拦下来，引用的文件缺一个即判定构建失败。
#
# 用 Central 上的 Starter 时（DOC_TEMPLATE_VERSION 非空）这份产物不参与镜像，
# 仍然要检查：仓库里的状态坏了，下一次发版就会把它打进 jar 发出去。
# ---------------------------------------------------------------------------
# 下面 shell 变量一律写成 \$：Dockerfile 在执行前会把 $NAME 换成构建参数的值，
# 未定义的变量换成空串，脚本会被静默改坏。
RUN set -eu; \
    UI=/src/backend/doc-template-spring-boot-starter/src/main/resources/META-INF/resources/doc-template; \
    grep -o '/doc-template/assets/[A-Za-z0-9._-]*' "\$UI/index.html" \
      | sed 's#^/doc-template/##' | sort -u > /tmp/ui-refs; \
    if [ ! -s /tmp/ui-refs ]; then \
      echo "错误：\$UI/index.html 未引用任何 assets 资源，管理页产物不完整"; \
      exit 1; \
    fi; \
    ( cd "\$UI" && find . -type f | sed 's#^\./##' | sort ) > /tmp/ui-files; \
    MISSING=\$(comm -23 /tmp/ui-refs /tmp/ui-files); \
    if [ -n "\$MISSING" ]; then \
      echo "错误：内嵌管理页产物与 index.html 不一致，以下文件不存在："; \
      printf '%s\n' "\$MISSING" | sed 's/^/  缺失 /'; \
      echo "成因通常是前端产物只重建或只提交了一部分，或者分支合并后没有重新构建。"; \
      echo "修复：pnpm --filter doc-template-frontend-ui build 之后，把 index.html 与 assets/ 一并提交。"; \
      exit 1; \
    fi; \
    echo "内嵌管理页产物自检通过：\$(wc -l < /tmp/ui-refs) 个资源引用全部存在"

# -U 不能去掉：上面 go-offline 若撞上阿里云的 404，Maven 会把「该坐标不存在」写进 ~/.m2 的
# 负缓存，而同一次构建的两步共用同一层文件系统。没有 -U 时 package 会直接沿用那个结论，
# 报错是 "was not found ... during a previous attempt. This failure was cached"，
# 看起来像版本不存在，实际是缓存里的旧结论。
RUN set -eu; \
    if [ -f /root/build-settings.xml ]; then SETTINGS="-s /root/build-settings.xml"; else SETTINGS=""; fi; \
    if [ -n "$DOC_TEMPLATE_VERSION" ]; then \
      echo "===== Starter 取中央仓库版本 $DOC_TEMPLATE_VERSION（不构建 core / starter）====="; \
      mvn -B -U $SETTINGS -pl doc-template-example \
          -Ddoc-template.version="$DOC_TEMPLATE_VERSION" -DskipTests package; \
    else \
      echo "===== Starter 取仓库源码（连带构建 core / starter）====="; \
      mvn -B -U $SETTINGS -pl doc-template-example -am -DskipTests package; \
    fi

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
