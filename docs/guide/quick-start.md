# 快速开始

::: tip 想先看看长什么样
不用本地起服务，直接打开在线演示 **[http://www.violateer.top:9150/doc-template](http://www.violateer.top:9150/doc-template)**（账号 `admin / Admin@123`）：模板管理、数据块配置、模拟生成、PDF 导出都能直接点。内置的示例模板是只读的，想试完整流程就自己新建一个。
:::

## 环境要求

| 依赖 | 版本 |
| --- | --- |
| JDK | 17+ |
| Maven | 3.8+ |
| Spring Boot | 3.x 或 4.x（Servlet Web，WebFlux 不支持） |
| 一个 `DataSource` Bean | 任何连接池都行，Starter 不建自己的池 |
| 数据库 | MySQL 8 / MariaDB（自动建表支持这两种） |

前端源码构建额外需要 Node.js 20+、pnpm 9+，但**使用 Starter 不需要前端环境** —— 管理页的构建产物已经打进 JAR。

::: tip 与业务框架无关
Starter 不绑定任何业务框架、认证框架、权限体系，也不要求宿主用特定 ORM。**不需要**任何认证框架、权限码或菜单表、前端构建环境、Office / LibreOffice / WPS。

持久层是 Spring JDBC（`JdbcTemplate` + 手写 SQL），不传递 MyBatis / MyBatis-Plus 依赖；宿主用不用 ORM、用哪个版本，都不会和这个模块互相影响。
:::

## 引入依赖

```xml
<dependency>
  <groupId>io.github.violateer</groupId>
  <artifactId>doc-template-spring-boot-starter</artifactId>
  <version>0.1.2</version>
</dependency>
```

Starter 会自动装配以下内容（无需 `@EnableXxx`）：

- 六张元数据表的读写（`repository` 包，基于 `JdbcTemplate`）与对应的 `DataSource` 绑定；
- 拦截 `/api/doc-template/**` 的认证拦截器；
- 静态资源 `/doc-template/**`；
- 启动期的缺表检测与建表。

## 最小配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/doc_template?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8
    username: root
    password: ${DB_PASSWORD}

doc-template:
  enabled: true
  auth:
    mode: none      # 演示/内网用；生产见「认证接入」
  # datasource 整段不配 = 复用上面的宿主数据源
  # storage    整段不配 = 文件落 ./data/doc-template
```

启动后访问 `http://localhost:9150/doc-template/`：

- 模板管理：`/doc-template/#/templates`
- 生成记录：`/doc-template/#/generate-records`

## 走一遍完整流程

1. **建模板**：模板管理 → 新建，填编码、名称、文件类型（DOCX/XLSX）、导出类型（ORIGINAL/PDF）。
2. **上传文件**：编辑模板 → 上传 `.docx` / `.xlsx`，占位符直接写在文件里（写法见[模板标记写法](./template-syntax)）。
3. **配数据块**（可选）：模板编辑弹窗第二个 Tab，按字段填表配好取数方式。
4. **看占位符**：`GET /api/doc-template/templates/{id}/placeholders` 会列出模板里识别到的 `${key}`。
5. **模拟生成**：点「开始生成」，后端按 `sort_order` 执行数据块 → 渲染 → 落生成记录。
6. **取文件**：生成记录里下载 / 预览，或直接走 `/generate-records/{id}/file`。

::: tip 不想手工建模板
仓库根目录的 `scripts/seed-demo.bat` / `scripts/seed-demo.sh` 可以把四套示例模板连文件带数据块、字典一次性刷进当前运行的系统，见[示例模板 · 导入方式](./examples#导入方式)。
:::

## 校验是否接对了

```bash
# 已注册的 Provider 列表（数据块 JAVA 类型下拉项来源）
curl http://localhost:9150/api/doc-template/system/providers
```

启动日志里会打印一块配置摘要，数据源、存储、认证、管理页一眼可对照：

```
========================================================================
 ____   ___   ____        doc-template · 文档模板引擎
|  _ \ / _ \ / ___|       Spring Boot Starter 0.1.2
| | | | | | | |
| |_| | |_| | |___
|____/ \___/ \____|
========================================================================
  数据源     宿主数据源(dataSource) · MySQL 8.0.36
  文件存储   本地磁盘 /app/data/doc-template（默认）
  认证       none · 不校验登录态
  管理页     http://localhost:9150/doc-template/
  数据块     启用 · SQL 取数启用 · 默认 500 行 / 5000ms
  元数据表   缺失，已自动创建
========================================================================
```

## 本地试跑（示例工程）

仓库里的 `backend/doc-template-example` 是**作者自用的示例宿主，不属于发布包**，只用来快速验证能力：

```bash
cd backend
mvn clean package

# 连真实 MySQL + MinIO（dev 走环境变量、local 写个人文件，两个 profile 都已开启认证）
# example 不自带内存库，必须带 dev 或 local 启动
mvn -pl doc-template-example -am spring-boot:run -Dspring-boot.run.profiles=dev

# 想关掉认证
java -jar doc-template-example/target/doc-template-example-0.1.2.jar --spring.profiles.active=dev --doc-template.auth.mode=none
```

示例账号 `admin / Admin@123`、`demo / Demo@123`；登录页 `/demo/login.html`，登录后 token 写入 `localStorage.token`。
这套登录实现（内存用户、Cookie 配置）**只服务于示例**，生产宿主应当接自己的认证体系。

::: warning 关于示例工程
`doc-template-example` 的代码不在发布物范围内：它的配置、登录实现、示例脚本都不保证向后兼容，也不应作为 API 参考。内嵌管理页的源码在 `frontend/doc-template-ui`，源码不单独发包，构建产物随 Starter JAR 发布。
:::

## 下一步

- 想让模板能取业务数据 → [数据块取数](./data-blocks)
- 想接自己的登录体系 → [认证接入](./auth)
- 想接对象存储 → [文件存储](./storage)
