# 数据源接入

## 设计原则：Starter 不自带数据库连接

`doc-template-spring-boot-starter` 的 `resources` 目录里**没有任何 `application.yml`**，启动时也不会创建连接池。它只做两件事：

1. 从宿主容器里拿一个现成的 `DataSource`，用它建 Mapper、建元数据表、跑 SQL 数据块；
2. 文件存储也不预设外部服务 —— 宿主不配 `doc-template.storage` 时落本地磁盘，配了 `s3` 才连对象存储。

所以打包发布出去的 jar 天然是「零外部依赖配置」的，宿主配什么它就用什么。

## 三种接法

| 场景 | 配置 | 说明 |
| --- | --- | --- |
| 单库（推荐） | 只配宿主的 `spring.datasource`，**不配** `doc-template.datasource` | 元数据表和业务表同库，SQL 数据块 `dataSource` 留空即可 |
| 独立模板库 | 额外配 `doc-template.datasource.url` | Starter 另建一个 Hikari 池（`doc-template-pool`），元数据表落在该库；业务表仍在宿主库，SQL 数据块用 `dataSource: docTemplate` 指过去 |
| 宿主多数据源 | 不配 `doc-template.datasource` | 按 `dataSource` → `dynamicDataSource` → `masterDataSource` → `primaryDataSource` → `@Primary` 的顺序自动挑一个；也可在数据块里直接写 Bean 名 |

### 场景一：单库

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/doc_template?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8
    username: root
    password: ${DB_PASSWORD}

doc-template:
  enabled: true
  auth:
    mode: none
  # datasource 整段不配 = 复用上面的宿主数据源
```

### 场景二：模板元数据单独一个库

```yaml
doc-template:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/doc_template?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    driver-class-name: com.mysql.cj.jdbc.Driver # 可省略，按 URL 自动推断
    username: doc_template
    password: ${DOC_TEMPLATE_DB_PASSWORD}
    maximum-pool-size: 5
    connection-timeout: 30000
```

启动后会打印一块配置摘要，「数据源」一行就是实际选中的库：

```
  数据源     宿主数据源(dataSource) · MySQL 8.0.36
```

挑不到数据源会启动失败并提示「没有找到宿主应用的数据源」。

## 自动建表

- 启动时检测六张表，任意一张缺失就执行对应建表脚本；
- 脚本用 `CREATE TABLE IF NOT EXISTS`，**不会删除已有数据**；
- 支持 MySQL、MariaDB；
- `doc-template.initialize-schema: false` 可关闭，改由宿主的迁移流程管理。

## 驱动说明

Starter 已将 `com.mysql:mysql-connector-j` 作为运行时依赖引入，`doc-template.datasource.driver-class-name` 可不配置，默认根据 JDBC URL 自动识别；如使用其他数据库，需要自行添加对应 JDBC 驱动。

## 多租户 / 多数据权限场景注意

在带租户隔离或数据权限的系统里，SQL 数据块风险尤其大：宿主的 `dataSource` 通常有全表读写权限，而数据块里的 SQL 不走宿主 ORM 的租户插件，也不带数据权限注解 —— 配一条 `select * from biz_order` 就能绕过所有隔离。

建议直接用 Provider 承接业务取数（在 `DocumentDataProvider` 里调用宿主 Service，租户与数据权限全都生效），并关掉 SQL 数据源：

```yaml
doc-template:
  data-block:
    sql-enabled: false
```
