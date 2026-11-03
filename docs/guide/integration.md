# 宿主集成

Starter 与任何业务框架、认证框架、权限体系都无关。只要宿主是 **JDK 17+ 的 Spring Boot 3.x / 4.x Servlet Web 应用**，容器里有一个 `DataSource`，就能直接用。

## 0. 前置条件

| 条件 | 说明 |
| --- | --- |
| JDK 17+ | 编译目标 `release=17` |
| Spring Boot 3.x / 4.x | Servlet Web 应用；自动装配同时兼容两代 Boot 的包路径，WebFlux 不支持 |
| 一个 `DataSource` Bean | 任何连接池都行，Starter 不建自己的池 |
| 数据库 | MySQL 8 / MariaDB 可自动建表；其他库需自行执行建表 DDL |

不需要：任何 ORM、任何认证框架、权限码或菜单表、前端构建环境、Office / LibreOffice / WPS。

## 1. 引入依赖

先在本工程 `backend` 目录执行 `mvn clean install`，然后在宿主后端模块引入：

```xml
<dependency>
  <groupId>io.github.violateer</groupId>
  <artifactId>doc-template-spring-boot-starter</artifactId>
  <version>0.1.1</version>
</dependency>
```

Starter 使用 Java 17、Spring JDBC 和 Jackson，不携带任何认证框架依赖，也不传递任何 ORM 依赖。

## 2. 放行路径

宿主如果有全局认证拦截器，需要放行两个前缀：

| 前缀 | 内容 |
| --- | --- |
| `/doc-template/**` | 静态页面与资源 |
| `/api/doc-template/**` | REST 接口（由 Starter 自己的拦截器处理认证） |

## 3. 认证桥接

见[认证接入](./auth)。宿主只需一个 Bean：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> CurrentUserHolder.getUserId();   // 取不到返回 null
}
```

## 4. iframe 嵌入

```vue
<template>
  <iframe
    src="/doc-template/?layout=bare&tokenStorageKey=替换为宿主存储键&tokenHeader=Authorization&tokenPrefix=Bearer"
    class="h-full w-full border-0" />
</template>
```

**嵌进宿主内容区时记得加 `layout=bare`**：页面默认带左侧栏（品牌 + 菜单 + 页脚）和顶栏标题，直接嵌进来会和宿主的菜单叠成两条导航。`layout=bare` 把这两块都去掉，页面上只剩内容区，切页面由宿主的菜单改 iframe 的 hash（`#/templates`、`#/generate-records`）。不传或写 `layout=full` 就是完整布局。

内嵌前端使用同源路径，默认从 `localStorage.token` 读取 Token 并发送 `Authorization: Bearer <token>`。宿主实际使用不同 storage key / header / prefix 时，通过非敏感查询参数配置 —— **不要把 Token 本身放到 URL**。

## 5. 代理配置

开发期前后端分离时，Vite 代理：

```ts
server: {
  proxy: {
    '/api/doc-template': { target: 'http://127.0.0.1:9150', changeOrigin: true },
    '/doc-template': { target: 'http://127.0.0.1:9150', changeOrigin: true }
  }
}
```

生产 Nginx：

```nginx
location /doc-template/ { proxy_pass http://127.0.0.1:9150/doc-template/; }
location /api/doc-template/ { proxy_pass http://127.0.0.1:9150/api/doc-template/; }
```

同源访问是最省事的做法：预览/下载、Cookie、`localStorage` 全都自然对齐。

## 6. Provider 扩展

在宿主项目实现 `DocumentDataProvider` 并注册为 Spring Bean：

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

数据查询继续调用宿主 Service，保持宿主数据权限与租户规则。

## 7. 上线检查清单

- [ ] `doc-template.auth.mode` 是否已设为 `custom`
- [ ] `DocTemplateAuthorizer` Bean 是否已在请求线程内取到身份
- [ ] 全局拦截器是否放行两个前缀
- [ ] 对外系统是否已 `data-block.sql-enabled: false`
- [ ] S3 模式下 bucket 是否已创建并授权
- [ ] 容器镜像内是否有中文字体文件
- [ ] 预览/下载地址在宿主的鉴权方式下是否可用（Cookie 或 Blob）
