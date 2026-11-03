# 认证接入

## 设计边界

模块只识别「当前调用方是谁」（用于生成记录的操作人），**不做权限码校验，也不创建用户/角色/登录表**。

- 不需要给宿主菜单/角色配任何权限码；
- 不需要 `sys_menu` 按钮；
- 拦截器只作用于 `/api/doc-template/**`，静态页面 `/doc-template/**` 永远放行（页面本身不含数据）。

## 两种模式

`doc-template.auth.mode` 只有两个取值：

| mode | 行为 | 宿主需要做 | 未登录时 |
| --- | --- | --- | --- |
| `none`（默认） | 不校验登录态，操作人取 `anonymous-user`；配了 `user-header` 则从该请求头取 | 什么都不用做 | 不拦截 |
| `custom` | 使用宿主注册的 `DocTemplateAuthorizer` Bean | 写一个 Bean（几行代码） | 实现返回 `null` → 401 |

```yaml
doc-template:
  auth:
    mode: none            # none | custom
    anonymous-user: anonymous
    # user-header: X-User-Id   # 仅 none 模式：从网关透传的可信请求头取操作人
```

启动时会打印生效模式与身份来源；`none` 模式额外打一条 WARN，提醒接口对外开放。

::: warning mode 是唯一权威
`none` 强制走 Noop（即使宿主注册了 Bean 也不启用）；`custom` 找不到 Bean **启动即失败**，不允许静默退化成无认证。`mode` 是启动期配置，切换需要重启 —— 这是有意的，认证开关不应该在运行时被动态改掉。
:::

## 接入宿主已有的身份来源

只有一个方法要写：返回当前调用方标识，取不到返回 `null`。身份从哪来由宿主决定，Starter 不关心。

**从宿主自己的请求上下文取**（最常见，登录态由宿主的过滤器/拦截器写入）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> CurrentUserHolder.getUserId();   // 取不到就返回 null
}
```

**从网关透传的请求头取**（网关已鉴权，模块只读头）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String userId = request.getHeader("X-User-Id");
        return (userId == null || userId.isBlank()) ? null : userId;
    };
}
```

**从请求自带的 Token 解析**（不依赖任何框架，自己解析即可）：

```java
@Bean
DocTemplateAuthorizer docTemplateAuthorizer() {
    return () -> {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        return tokenService.resolveUserId(request.getHeader("Authorization"));   // 失败返回 null
    };
}
```

然后打开开关：

```yaml
doc-template:
  auth:
    mode: custom
```

### 写实现时注意三点

1. **不要抛异常**，未登录就返回 `null`。抛出去会被兜底成 500，而不是 401。
2. **实现要轻**：每个 `/api/doc-template/**` 请求都会调一次，不要在里查库。
3. **必须在请求线程内取得到身份**。宿主如果用异步线程池处理请求，要自己在实现里把上下文带过去（`RequestContextHolder` 或自建 `ThreadLocal` 透传）。

## `none` 模式：网关已经鉴权的情况

如果网关层做了统一鉴权、模块只想在生成记录里留下真实操作人，不用写 Bean，配一个透传请求头即可（模块只读这个头，不校验）：

```yaml
doc-template:
  auth:
    mode: none
    user-header: X-User-Id   # 值由网关写入，模块直接拿来当操作人
```

::: danger 生产环境不要用 none
这个模式下 `/api/doc-template/**` 对所有能访问到端口的调用方开放。对外开放的系统请用 `custom`，并同时关掉 SQL 数据源（`data-block.sql-enabled: false`）。
:::

## 前端 token 对齐

内嵌管理页默认从 `localStorage.token` 读值，按 `Authorization: Bearer <token>` 发送。宿主用别的存储键/请求头/前缀时，通过 iframe 查询参数传进去（**不要把 token 本身放 URL**）：

```html
<iframe src="/doc-template/?layout=bare&tokenStorageKey=Admin-Token&tokenHeader=Authorization&tokenPrefix=Bearer"></iframe>
```

`layout=bare` 用来隐藏内嵌页的左侧栏和顶栏（嵌进宿主内容区时不加会和宿主菜单叠成两条导航），详见 [iframe 嵌入](./ui#关于左侧栏)。

401 时前端可用 `DOC_TEMPLATE_CONFIG.loginUrl` 指定登录页，自动跳转并带 `redirect` 参数。

## 未登录时跳登录页

配 `doc-template.auth.login-page` 后，未登录打开管理页会 **302 到该地址**，不用宿主自己写拦截器：

```yaml
doc-template:
  auth:
    mode: custom
    login-page: /demo/login.html                 # 站内页面（示例自带的登录页）
    # login-page: https://sso.example.com/login  # 或者外部统一登录
```

| 取值 | 行为 |
| --- | --- |
| 留空（默认） | 不跳转，页面照常打开，接口返回 401 由前端提示 |
| 站内路径 | 302 到该路径，自动带 `?redirect=<原地址>` |
| 完整地址 | 同上，用于接公司 SSO / 统一认证 |

两条实现细节，接自有登录页时值得知道：

- **只跳页面，不跳静态资源**。`/doc-template/assets/*.js|css` 照常返回，否则登录页自己的脚本都加载不出来。
- **`index.html` 会被注入 `window.DOC_TEMPLATE_CONFIG.loginUrl`**，所以「页面已经打开、会话中途过期」时前端也会跳登录页，而不是只弹一串 401。

`mode: none` 下不生效——没有认证就没有「未登录」这回事。

## 启动行为对照

| 配置 | 缺少 | 启动行为 |
| --- | --- | --- |
| `mode: custom` | `DocTemplateAuthorizer` Bean | 抛 `IllegalStateException`，提示实现接口 |
| `mode: none` | — | 正常启动 + WARN 日志 |

非 `none` 模式下未认证返回 `401 {"code":401,"msg":"..."}`。

## 为什么 Starter 不带适配器

Starter 的 pom 里**没有任何认证框架依赖**，也不提供内置适配器。宿主桥接成本只有 3~5 行，上下文透传、缓存这些细节也由宿主自己掌握。

`doc-template-example` 里的 `DemoDocTemplateAuthorizer` 就是一行桥接的示例（该模块不在发布包范围内，仅供参照）。
