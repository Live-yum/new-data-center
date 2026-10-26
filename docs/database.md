# PostgreSQL / 人大金仓数据库适配

## 适配范围

默认业务数据库为 PostgreSQL，可通过 Spring profile 切换为 KingbaseES。目标金仓模式为 **PostgreSQL 兼容模式**；Oracle/MySQL 兼容模式需要另行验证。业务数据仍由原有 `system`、`infra` 模块、MyBatis-Plus Mapper 和租户拦截器管理。紫金桥实时库使用独立 HTTP 客户端，与这里的关系数据库配置互不替代。

仓库上游已经提供两套表结构、初始化数据及序列：

| 数据库 | 业务初始化 SQL | Quartz SQL |
| --- | --- | --- |
| PostgreSQL | `yudao-cloud-mini-master-jdk17/sql/postgresql/ruoyi-vue-pro.sql` | 同目录 `quartz.sql` |
| KingbaseES | `yudao-cloud-mini-master-jdk17/sql/kingbase/ruoyi-vue-pro.sql` | 同目录 `quartz.sql` |

保留这些 SQL 的表名、字段名和 `*_seq` 名称，避免改写原项目的 DO、代码生成模板和 Mapper 包结构。`dal/mysql` 是原项目的包名，并不表示运行时只能连接 MySQL。

## 本次修复

1. PostgreSQL 和 `kingbase8` JDBC 驱动改为 MyBatis starter 的传递依赖，同时保留 MySQL 驱动的传递依赖。聚合服务 `yudao-server` 与独立 `system`、`infra` 服务都能获得这些驱动，已有 MySQL 部署和代码生成器连接 MySQL 外部数据源无需另加驱动；默认业务数据库仍为 PostgreSQL。
2. 添加 `application-postgresql.yaml`、`application-kingbase.yaml`，分别导入共享 `yudao-database-*.yaml`。数据库地址、用户名、密码、schema 和完整 JDBC URL 均可通过环境变量配置，配置文件不包含实际密码。
3. 使用 `INPUT` 主键策略，沿用 DO 上的 `@KeySequence` 和已有 PostgreSQL / Kingbase key generator。分页插件根据 JDBC 方言生成分页 SQL。
4. 业务 SQL 混用了 `BOOL` 和 `int2` 布尔列，添加 `NumericBooleanTypeHandler`，仅在两套数据库 profile 中通过 `yudao.mybatis.numeric-boolean=true` 注册。显式 `jdbcType` 优先，否则读取 PreparedStatement 参数元数据：整数列绑定 `0/1`，原生布尔列继续绑定 Boolean，保留 SQL NULL。这也覆盖 MyBatis Wrapper 的布尔查询参数，不依赖 Wrapper 是否保留 `@TableField` 注解。Quartz 使用自己的 JDBC delegate。
5. 修复访问令牌、刷新令牌、访问日志、异常日志四个清理 Mapper 的 `DELETE ... LIMIT`。改为按主键排序的有界子查询再物理删除，兼容 PostgreSQL / KingbaseES，保留每批上限和原有物理清理语义。
6. 修复数据库产品名识别的大小写、未知名称和重复 SQL Server 产品名导致的问题；名称无法识别时根据 JDBC URL 判断。主数据源未显式命名时使用 dynamic-datasource 的默认 `master`。
7. PostgreSQL 和金仓 PG 模式的 Quartz `BYTEA` 表统一使用 `PostgreSQLDelegate`；自动推断值仅加入当前 Spring Environment，不再污染进程级系统属性。显式配置仍优先。
8. 禁止应用启动时自动执行初始化 SQL，避免误重建已有表；新增只监听本机地址的 PostgreSQL + Redis 开发 Compose 文件。

### 布尔字段核对

以下为当前启用 `system`、`infra` 模块全部直接声明的 17 个 Boolean 字段及公共继承字段。PostgreSQL、Kingbase 两套原 SQL 中的类型一致：

| 表 / 公共类 | 列 | 类型 |
| --- | --- | --- |
| 所有继承 `BaseDO` 的业务表 | `deleted` | `int2` |
| `system_sms_code` | `used` | `int2` |
| `system_notify_message` | `read_status` | `bool` |
| `system_oauth2_approve` | `approved` | `bool` |
| `system_menu` | `visible`, `keep_alive`, `always_show` | `bool` |
| `system_mail_account` | `ssl_enable`, `starttls_enable` | `bool` |
| `infra_config` | `visible` | `bool` |
| `infra_file_config` | `master` | `bool` |
| `infra_codegen_table` | `sub_join_many` | `bool` |
| `infra_codegen_column` | `nullable`, `primary_key`, `create_operation`, `update_operation`, `list_operation`, `list_operation_result` | `bool` |

不能将所有 Boolean 参数一律绑定为数字，否则站内信 `read_status = false` 等查询会在 PostgreSQL 上报 `boolean = smallint`。当前处理器依赖驱动的 JDBC `ParameterMetaData`，不会吞掉元数据或 SQL 异常再猜测类型。参数元数据获取可能增加预编译/Describe 开销；没有按 SQL 文本跨连接缓存元数据，也不持有连接。金仓仍需用匹配版本的厂商驱动在目标环境验证此能力。

## 环境变量

| 变量 | PostgreSQL 默认值 | KingbaseES 默认值 | 说明 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `postgresql` | 需设为 `kingbase` | 两者选择一个 |
| `DB_HOST` | `127.0.0.1` | `127.0.0.1` | 数据库地址 |
| `DB_PORT` | `5432` | `54321` | JDBC 端口 |
| `DB_NAME` | `ruoyi-vue-pro` | `ruoyi-vue-pro` | 数据库名称 |
| `DB_USERNAME` | `yudao` | `yudao` | 应用账号 |
| `DB_PASSWORD` | 无默认值，必填 | 无默认值，必填 | 从部署环境注入 |
| `DB_SCHEMA` | `public` | `public` | JDBC `currentSchema`；表和序列须在同一 schema |
| `DB_JDBC_URL` | 根据上述变量生成 | 根据上述变量生成 | 可完整覆盖，便于 SSL、集群和厂商连接参数配置 |

两个默认 JDBC URL 分别为：

```text
jdbc:postgresql://127.0.0.1:5432/ruoyi-vue-pro?currentSchema=public
jdbc:kingbase8://127.0.0.1:54321/ruoyi-vue-pro?currentSchema=public
```

请在金仓上使用 `com.kingbase8.Driver` 和 `jdbc:kingbase8:`。驱动版本默认沿用仓库 BOM 的 `kingbase.jdbc.version`（`9.0.1.jre7`）；部署 V8R6 时应按厂商认证版本覆盖该属性，例如：

```bash
mvn -Dkingbase.jdbc.version=8.6.0 -pl yudao-server -am package -DskipTests
```

这里的 `8.6.0` 是金仓 V8 官方 JDBC 文档列出的示例，不代表已经完成对任意 V8 小版本的认证。实际驱动应与部署的服务端版本匹配。

## PostgreSQL 本地启动

在仓库根目录操作，先在当前终端安全设置 `DB_PASSWORD`，然后执行：

```bash
docker compose -f yudao-cloud-mini-master-jdk17/script/docker/docker-compose.postgresql.yml up -d

cd yudao-cloud-mini-master-jdk17
mvn -pl yudao-server -am package -DskipTests
java -jar yudao-server/target/yudao-server.jar --spring.profiles.active=postgresql
```

Compose 将首次初始化脚本挂载到新 PostgreSQL 数据卷的 `docker-entrypoint-initdb.d`。已有数据卷不会重复初始化。默认 schema 为 `public`。如使用其他 schema，请自行先创建 schema、设置导入会话的 `search_path`，再执行 SQL，并同步设置 `DB_SCHEMA`。

**初始化 SQL 包含 `DROP TABLE` / `DROP SEQUENCE`，只可用于新的空数据库。** 它们是上游的完整示例数据集，不是生产增量迁移。已有 MySQL 数据需先备份，使用获准的迁移工具完成数据搬迁、类型转换和序列对齐后再切换连接；本次没有连接或迁移任何生产数据库。

已有 PostgreSQL 实例可用 `psql` 手动初始化，密码通过交互输入或客户端凭证文件提供：

```bash
psql -h 127.0.0.1 -p 5432 -U yudao -d ruoyi-vue-pro -v ON_ERROR_STOP=1 \
  -f sql/postgresql/ruoyi-vue-pro.sql
psql -h 127.0.0.1 -p 5432 -U yudao -d ruoyi-vue-pro -v ON_ERROR_STOP=1 \
  -f sql/postgresql/quartz.sql
```

以上两个命令在后端目录中执行；需预先创建数据库和有建表权限的账号。应用账号后续至少需要业务 schema 的 `USAGE`、表的读写权限以及序列的 `USAGE` / `SELECT` 权限。

## KingbaseES 部署

1. 准备具有有效授权的 KingbaseES 实例，并确认使用 PostgreSQL 兼容模式。仓库不分发金仓服务端镜像或许可证。
2. 在新的空数据库内，使用金仓 `ksql` 执行 `sql/kingbase/ruoyi-vue-pro.sql`，按需执行 `sql/kingbase/quartz.sql`。命令参数与实际安装的客户端版本保持一致，并启用遇错停止。
3. 设置 `SPRING_PROFILES_ACTIVE=kingbase`，以及实际 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`。使用自定义 schema 时同步配置 `DB_SCHEMA`。
4. 使用与服务端匹配的 `kingbase.jdbc.version` 构建，启动 `yudao-server`。验证登录、菜单、分页、增删改、字典、文件存储和代码生成。

微服务部署时，可在 `system`、`infra` 各自的外部配置或 Nacos 配置中导入以下共享资源，并使用它们自己的服务端口和服务发现配置：

```yaml
spring:
  config:
    import: classpath:yudao-database-kingbase.yaml
```

PostgreSQL 对应 `classpath:yudao-database-postgresql.yaml`。共享文件位于 MyBatis starter 内，无需在每个业务模块复制数据源细节。若使用不同业务数据库，分别设置各服务的 `DB_*` 环境变量。

## 验证与回归

### 服务运行时的 JDBC 驱动

JDBC 驱动必须进入实际服务的运行时依赖；仅在直接声明驱动的 MyBatis starter 内通过测试，无法证明 `optional` 依赖能传递给最终服务。`yudao-server` 中的 `JdbcDriverAvailabilityTest` 使用其自身的依赖，通过 `DriverManager.getDriver(...)` 实际发现 MySQL、PostgreSQL、Kingbase 驱动并检查对应 URL，不手工加载驱动类，也不添加额外的测试驱动依赖。该测试不连接数据库：

```bash
mvn -pl yudao-server -am -Dtest=JdbcDriverAvailabilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

可同时检查聚合服务和两个独立服务的 MySQL 运行时依赖路径：

```bash
mvn -pl yudao-server,yudao-module-system/yudao-module-system-server,yudao-module-infra/yudao-module-infra-server \
  -am dependency:tree -Dscope=runtime -Dincludes=com.mysql:mysql-connector-j
```

三项服务应通过 `yudao-spring-boot-starter-mybatis` 解析到 `com.mysql:mysql-connector-j`，不能只在 starter 自身看到 `(optional)`。这是驱动可用性验证，不代表已完成 MySQL 服务端连接或数据迁移验证。

### PostgreSQL / Kingbase 数据库行为

本次在真实 PostgreSQL 16.9 临时实例上导入了 PostgreSQL 的业务和 Quartz SQL：成功建立 **60 张表、48 个业务序列**。当前启用模块的所有 `@TableName` 和 `@KeySequence` 都能在初始化脚本中找到对应对象。

新增 MyBatis 回归测试覆盖：

- 两种 JDBC 驱动的类加载和协议识别；产品名识别；主键策略及显式配置优先级。
- 布尔值 `true` / `false` / `NULL` 的 SMALLINT 写入和读取，以及 JDBC 原生 BOOL 的独立绑定。
- 在真实数据库的临时表中验证序列取号、BOOL / SMALLINT 混合字段的 Lambda 查询、BYTEA 文件内容、分页总数、更新、逻辑删除和分批物理删除。
- 使用实际 `NotifyMessageMapper` 和 `SmsCodeMapper`，复制真实表结构为会话临时表，验证站内信未读数/已读更新、短信验证码 `used` 和继承 `deleted` 字段的布尔查询。

普通单元测试不需要数据库。实际连接测试通过环境变量显式启用，只创建会话级临时表，不写业务表：

```bash
# 在后端目录运行，先设置 DATABASE_TEST_PASSWORD。
DATABASE_TEST_URL='jdbc:postgresql://127.0.0.1:5432/ruoyi-vue-pro?currentSchema=public' \
DATABASE_TEST_USERNAME=yudao \
mvn -pl yudao-framework/yudao-spring-boot-starter-mybatis -am \
  -Dtest=DatabaseConfigurationTest,NumericBooleanTypeHandlerTest,DatabaseCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

金仓可用同一组测试，将 `DATABASE_TEST_URL` 换成 `jdbc:kingbase8:` 连接串，并按需传入 `-Dkingbase.jdbc.version=...`。当前没有可访问的金仓服务端，因此金仓的真实连接、厂商驱动与服务端匹配、权限及运行模式仍需在目标环境执行这组验证。PostgreSQL 验证不能代替金仓实机验证。

实际业务 Mapper 的测试要求已导入对应业务 SQL，执行方式为：

```bash
DATABASE_TEST_URL='jdbc:postgresql://127.0.0.1:5432/ruoyi-vue-pro?currentSchema=public' \
DATABASE_TEST_USERNAME=yudao \
mvn -pl yudao-module-system/yudao-module-system-server -am \
  -Dtest=DatabaseConfigurationTest,NumericBooleanTypeHandlerTest,DatabaseCompatibilityTest,BooleanColumnDatabaseTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试同样只写会话临时表，不写原业务表。

## 官方参考

- [PostgreSQL JDBC 连接与参数](https://jdbc.postgresql.org/documentation/use/)
- [KingbaseES V8 JDBC 驱动包与版本](https://help.kingbase.com.cn/v8/development/client-interfaces/jdbc/jdbc-1.html)
- [KingbaseES V8R6 JDBC 连接、驱动类与 currentSchema 参数](https://bbs.kingbase.com.cn/kingbase-doc/v8.6.7.24/development/client-interfaces/jdbc/jdbc-2.html)
