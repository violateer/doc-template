# 集成说明

Starter 与任何业务框架、认证框架、权限体系都无关。只要宿主是 **JDK 17+ 的 Spring Boot 3.x / 4.x Servlet Web 应用**，容器里有一个 `DataSource`，就能直接用。

## 1. 前置条件

| 条件 | 说明 |
| --- | --- |
| JDK 17+ | 编译目标 `release=17` |
| Spring Boot 3.x / 4.x | Servlet Web 应用；自动装配同时兼容两代 Boot 的包路径，WebFlux 不支持 |
| 一个 `DataSource` Bean | 任何连接池都行，Starter 不建自己的池（除非配了 `doc-template.datasource.url`） |
| 数据库 | MySQL 8 / MariaDB 可自动建表；其他库需自行执行建表 DDL |

不需要的东西：不需要任何 ORM、不需要任何认证框架、不需要权限码、不需要前端构建环境、不需要 Office / LibreOffice。

## 2. 引入依赖

```bash
cd backend
mvn clean install
```

```xml
<dependency>
  <groupId>io.github.violateer</groupId>
  <artifactId>doc-template-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

引入即生效，不需要 `@EnableXxx`。

## 3. 放行路径

宿主如果有全局过滤器/拦截器，放行两个前缀：

| 前缀 | 内容 |
| --- | --- |
| `/doc-template/**` | 静态页面与资源 |
| `/api/doc-template/**` | REST 接口（由 Starter 自己的拦截器处理认证） |

## 4. 认证接入

```yaml
doc-template:
  auth:
    mode: custom   # none | custom
```

宿主实现一个 Bean 即可，身份来源随便（会话、Token、网关透传头、自建上下文）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> CurrentUserHolder.getUserId();   // 取不到返回 null
}
```

- 未认证返回 `null` → 接口 401；**不要抛异常**。
- 实现要轻：每个 `/api/doc-template/**` 请求都会调一次。
- 必须在请求线程内取得到身份；异步线程池要自己透传上下文。

`none` 模式（默认）不校验登录态，操作人取 `anonymous-user`，或从 `user-header` 指定的请求头取（网关已鉴权时用）。**生产环境不要用 `none`。**

## 5. iframe 嵌入

```vue
<template>
  <iframe
    src="/doc-template/?tokenStorageKey=token&tokenHeader=Authorization&tokenPrefix=Bearer&layout=bare"
    class="h-full min-h-[70vh] w-full border-0" />
</template>
```

内嵌页面默认从 `localStorage.token` 读 Token，按 `Authorization: Bearer <token>` 发送。宿主用别的键/头/前缀时通过查询参数配置，**不要把 Token 本身放到 URL**。

`layout=bare` 把内置页面的左侧栏（品牌 + 菜单 + 页脚）和顶栏标题都去掉，页面上只剩内容区。**嵌进宿主内容区时建议都带上**——不带的话宿主菜单和内置菜单会叠成两条导航。切页面由宿主的菜单负责，改 iframe 的 hash 即可：`/doc-template/?layout=bare#/templates`、`/doc-template/?layout=bare#/generate-records`。不传或 `layout=full` 保持完整布局。

## 6. 代理

开发期前端与后端分离时，把两个前缀代理到后端（见 `vite.proxy.example.ts`）：

```ts
server: {
  proxy: {
    '/doc-template': { target: 'http://127.0.0.1:9150', changeOrigin: true },
    '/api/doc-template': { target: 'http://127.0.0.1:9150', changeOrigin: true }
  }
}
```

生产环境同源部署最省事（见 `nginx.conf.example`）：预览/下载、Cookie、`localStorage` 全都自然对齐。

## 7. Provider 扩展

```java
@Component
public class ReportProvider implements DocumentDataProvider {
    private final ReportService reportService;   // 宿主自己的 Service

    @Override public String getCode() { return "report"; }

    @Override public Object execute(Map<String, Object> params) {
        return reportService.detail((String) params.get("reportNo"));
    }
}
```

数据查询继续走宿主 Service，业务规则与数据权限全都生效 —— 这是 SQL 数据块做不到的。已注册的 Provider 可用 `GET /api/doc-template/system/providers` 查看。

## 8. 数据源与存储

默认复用宿主 `DataSource`，元数据表与业务表同库；文件默认落 `doc-template.base-path`（`./data/doc-template`）。

要独立模板库或对象存储时才配：

```yaml
doc-template:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/doc_template?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: doc_template
    password: ${DOC_TEMPLATE_DB_PASSWORD}
  storage:
    type: s3
    endpoint: http://127.0.0.1:9000
    bucket: doc-template
    access-key: ${DOC_TEMPLATE_S3_ACCESS_KEY}
    secret-key: ${DOC_TEMPLATE_S3_SECRET_KEY}
```

启动后会打印一块配置摘要（横幅 + 数据源 / 文件存储 / 认证 / 管理页 / 数据块 / 元数据表），照着核对即可。

## 9. 上线检查

- [ ] `doc-template.auth.mode` 是否已设为 `custom`
- [ ] `DocTemplateAuthorizer` 是否能在请求线程内取到身份
- [ ] 全局过滤器是否放行两个前缀
- [ ] 对外系统是否已 `data-block.sql-enabled: false`
- [ ] S3 模式下 bucket 是否已创建并授权
- [ ] 容器镜像内是否有中文字体文件
- [ ] 预览/下载地址在宿主的鉴权方式下是否可用（Cookie 或 Blob）
