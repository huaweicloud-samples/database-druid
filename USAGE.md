# Druid 1.2.5 GaussDB 适配版 · 使用文档

> 基于官方 alibaba/druid **1.2.5** 改造，新增对 **GaussDB（含 Oracle 兼容模式 A、分布式）** 的完整支持。
> 改造参照 huaweicloud-samples/database-druid（1.2.25-SNAPSHOT）并适配 1.2.5 基线 API。
> 文档日期：2026-09-03

---

## 一、定位与改造范围

解决的问题：官方 1.2.5 不识别 `jdbc:gaussdb:` URL、无 gaussdb SQL 方言，WallFilter 启用后 GaussDB 业务 SQL 会被当作解析失败而拦截（华为生态仓 README 因此警告"不要启用 WallFilter"）。

本版本在 1.2.5 上补齐（全部为增量式改动，不影响 MySQL/Oracle/PG 等既有方言行为）：

| 层 | 内容 |
|---|---|
| SQL 方言 | `com.alibaba.druid.sql.dialect.gaussdb.*`：Lexer/ExprParser/StatementParser/SelectParser/CreateTableParser + AST + OutputVisitor + keywords（258 个） |
| WallFilter | `GaussDBWallProvider`/`GaussDBWallVisitor` + 独立规则目录 `META-INF/druid/wall/gaussdb/` |
| 连接池 | `GaussDBValidConnectionChecker`（保活）/`GaussDBExceptionSorter`（连接异常摘除）自动挂载 |
| 识别与路由 | `DbType.gaussdb`、URL/驱动类识别、`SQLParserUtils`/`SQLUtils` 解析与输出路由、XA 支持 |

## 二、构建

**环境**：JDK 8+（构建机 JDK 9+ 亦可），Maven 3.x。依赖 GaussDB JDBC 驱动（provided scope，**不打进 jar**）。

```bat
cd D:\workplace\code\Druid\druid-1.2.5
mvn package -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true
```

产物：`target/druid-1.2.5.jar`（坐标仍为 `com.alibaba:druid:1.2.5`，可直接替换官方 jar，无感切换）。

当前 pom 使用 `com.huaweicloud.gaussdb:gaussdbjdbc:507.0.0.B071`（Distributed/Jdk8，已装本地仓）。**客户 505.2.1.spc800 驱动到位后**：装仓 → 改 pom 版本 → 重新打包回归（三代驱动类名 `com.huawei.gaussdb.jdbc.Driver`/`core.BaseConnection`/`xa.PGXAConnection` 已验证同包名，切换风险低）。

驱动装仓命令：
```bat
mvn install:install-file -Dfile=gaussdbjdbc.jar ^
    -DgroupId=com.huaweicloud.gaussdb -DartifactId=gaussdbjdbc ^
    -Dversion=505.2.1.spc800 -Dpackaging=jar
```

## 三、接入使用

### 方式 A：替换官方 jar（推荐）

应用中 `com.alibaba:druid:1.2.5` 的依赖，用本工程产物替换（私服 deploy 或直接放 lib）。包名/类名完全不变，应用代码零改动。

### 方式 B：数据源配置

```java
DruidDataSource ds = new DruidDataSource();
ds.setUrl("jdbc:gaussdb://主机:端口/库名");          // jdbc:gaussdb: / jdbc:opengauss: 自动识别
ds.setUsername("user");
ds.setPassword("pwd");
ds.setDriverClassName("com.huawei.gaussdb.jdbc.Driver");
ds.setValidationQuery("SELECT 'x'");

WallFilter wallFilter = new WallFilter();            // 自动路由 GaussDBWallProvider
ds.setProxyFilters(Collections.singletonList(wallFilter));
ds.init();
```

Spring Boot（druid-spring-boot-starter 1.2.5 配置不变）：

```yaml
spring:
  datasource:
    url: jdbc:gaussdb://host:port/db
    driver-class-name: com.huawei.gaussdb.jdbc.Driver
    druid:
      filter:
        wall:
          enabled: true        # 自动按 URL 识别 gaussdb 并路由
```

### 自动生效项（无需配置）

- URL `jdbc:gaussdb:`/`jdbc:opengauss:` → `DbType.gaussdb`
- 驱动类 `com.huawei.gaussdb.jdbc.Driver` → 自动挂 GaussDBValidConnectionChecker / GaussDBExceptionSorter
- WallFilter 对 gaussdb → GaussDBWallProvider（gaussdb 方言解析，**非 PG 兜底**）

## 四、GaussDB 支持能力清单

**分布式 DDL**：`DISTRIBUTE BY HASH/ROUNDROBIN/REPLICATION`、`TO GROUP/NODE`、`PARTITION BY HASH/RANGE/LIST`（含 `START/END/EVERY` 间隔分区）、`PARTIAL CLUSTER KEY`、`UNLOGGED/GLOBAL/LOCAL/TEMP`、`ON COMMIT PRESERVE/DELETE ROWS`、`COMPRESS/NOCOMPRESS`、列存 `WITH(orientation=column)`、`COMPRESS_MODE`、`SLICE` 分布、`ENABLE/DISABLE ROW MOVEMENT`、`COMMENT`

**DML/查询**：`INSERT ... ON DUPLICATE KEY UPDATE`、`INSERT ... ON CONFLICT`、`RETURNING`、`MERGE INTO`、函数表源 `func(...) [AS] alias(col,...)`（generate_series 等）、`GROUP BY ... rollup()/cube()` 后置项、`VACUUM [DELTAMERGE|HDFSDIRECTORY ...]`、`wm_concat(...) over()`、`group_concat ... SEPARATOR`

**Oracle 兼容模式(A)语法**（20 项矩阵实测通过 wall）：`dual`/`SYSDATE`/`NVL`/`DECODE`/`TO_CHAR`/`TO_DATE`/`ROWNUM`/`(+)` 外连接/`START WITH ... CONNECT BY PRIOR`/序列 `nextval/currval`/`FOR UPDATE`/`LISTAGG WITHIN GROUP`/`CREATE SEQUENCE`/`CALL`/`MINUS`/`INTERSECT`

> A 模式注意：`ON CONFLICT` 为服务器端不支持（与 wall 无关），upsert 请用 `MERGE INTO` 或 `ON DUPLICATE KEY UPDATE`。

## 五、WallFilter 配置

### 灰度建议（上线初期）

```java
wallFilter.setLogViolation(true);      // 违规只记日志
wallFilter.setThrowException(false);   // 不拦截，观察期
```
观察业务 SQL 的 violation 日志（尤其 syntax error 类），确认无误报后再开 `throwException=true`（默认）。

### 常用 WallConfig 开关

| 开关 | 默认 | 说明 |
|---|---|---|
| `setSelectWhereAlwayTrueCheck(true)` | true | WHERE 恒真条件拦截（`WHERE 1=1`）；gaussdb 下恒假（`WHERE 1=2`）同样拦截 |
| `setSelectWhereSubQueryCheck(false)` | gaussdb 默认开 | WHERE 子查询拦截（`WHERE id IN (SELECT ...)`，含 EXISTS/ANY/ALL/SOME）；仅 GaussDBWallProvider 无参构造默认开启，其他方言默认关闭 |
| `setSelectUnionCheck(true)` | true | UNION 拦截；gaussdb 下 UNION / UNION ALL 均拦截（其他方言仅拦无 FROM 的右侧分支） |
| `setDeleteAllow(false)` | true | 禁 DELETE |
| `setTruncateAllow(false)` | true | 禁 TRUNCATE |
| `setMultiStatementAllow(false)` | false | 禁多语句 |
| `setStrictSyntaxCheck(true)` | true | 严格语法检查（解析失败即拦） |
| `setSelectLimit(n)` | -1 | 强制 LIMIT 上限 |

系统属性方式：`-Ddruid.wall.deleteAllow=false` 等（WallConfig.configFromProperties）。

### 2026-10 FVT 整改新增语义（gaussdb 专属）

- **deny-table / deny-schema 子查询 FROM 拦截**：`SELECT * FROM (SELECT * FROM pg_database) t` 拦截；顶层 `from pg_database` 仍豁免（与上游统一语义一致）
- **deny-schema 默认黑名单**：`META-INF/druid/wall/gaussdb/deny-schema.txt`（information_schema）
- **恒假条件**：`WHERE 1=2`（ErrorCode 2113）
- **WHERE 子查询**：见上表开关
- **空 SQL / 纯注释 SQL**：`GaussDBWallProvider.checkValid` 直接拒绝（返回 false）
- 以上增强仅作用于 gaussdb；其他方言（mysql/oracle/pg 等）行为与官方一致

### 黑名单文件（deny-function / deny-table）

**运行时加载的是 jar 内**：`META-INF/druid/wall/gaussdb/deny-function.txt`（25 个默认函数：pg_sleep、current_setting、current_user、inet_client_addr 等）/ `deny-table.txt`（pg_database）。

**源码位置**（改后需重打包）：`src/main/resources/META-INF/druid/wall/gaussdb/`

**应用侧追加（推荐，零侵入）**：在应用 `src/main/resources/` 下放同名文件，只写新增条目（一行一个）。加载机制为 classpath 枚举**合并**（实测 jar 默认 25 + 应用追加 2 = 27 生效），Spring Boot fat jar 同样生效。

**API 运行时增删**：

```java
wallFilter.getConfig().getDenyFunctions().add("my_func");   // 拦函数
wallFilter.getConfig().getDenyTables().add("secret_table"); // 拦表
```

## 六、自定义拦截 SQL（四层次）

| 需求形态 | 手段 | 代码量 |
|---|---|---|
| 拦函数/表 | wall 黑名单（文件追加或 API，见上节） | 零代码 |
| 拦整类语句 | WallConfig 开关 | 零代码 |
| 拦特定已知 SQL | `provider.addWhiteSql/addBlackSql`（规范化哈希精确匹配） | 零代码 |
| **拦任意特征**（CONNECT BY、正则、业务规则） | 自定义 Filter（druid 官方 Filter 链扩展点） | ~40 行 |

自定义 Filter 模板见 `D:\workplace\code\Druid\druid-gaussdb-demo\src\CustomBlockFilter.java`，与 WallFilter 并列挂载、互补使用（wall 管 AST 级规则，自定义 Filter 管全文匹配，无上下文限制）：

```java
CustomBlockFilter blocker = new CustomBlockFilter("custom rule violation");
blocker.addKeyword("connect by");
blocker.addPattern("(?i).*\\bbenchmark\\s*\\(.*");
ds.setProxyFilters(Arrays.asList(wallFilter, blocker));   // init 前一次性配置
```

## 七、测试

```bat
rem gaussdb 全套（单元 + Oracle 兼容矩阵 + 507 真库集成，库不可达自动跳过）
mvn test -Dtest="GaussDB*Test,GaussDbResourceTest" -Dcheckstyle.skip=true

rem 回归（wall 全方言 + PG 方言）
mvn test -Dtest="com.alibaba.druid.bvt.filter.wall.**.*Test"
mvn test -Dtest="com.alibaba.druid.bvt.sql.postgresql.**.*Test"
```

**当前基线（2026-09-03）**：gaussdb 套件 16/16、507 真库集成 6/6、wall 包回归 154/154、PG 方言 51/51 全绿。

已知历史遗留失败（与本次改造无关）：JdbcUtilsTest 2 个（上游测试自身断言类型缺陷）、PGTest（空套件）、PG_500_connection/getCreateTableScript（需真 PG 库）。

## 八、已知边界与注意事项

1. **wall 黑名单的上下文触发边界**（上游 1.2.5～1.2.25 统一语义，非本改造缺陷）：
   - deny-function 仅在 **WHERE/HAVING/ORDER BY/GROUP BY** 上下文触发，SELECT 列表中的 `select pg_sleep(1)` 不拦；
   - deny-table 顶层 `from pg_database`、`update/delete pg_database` 不拦；**gaussdb 下子查询 FROM 已拦截**（2026-10 整改，见第五节）；其他方言子查询 FROM 仍不拦（上游语义）；
   - 核心表防护请叠加**数据库侧权限控制**，wall 黑名单是作用域有限的深度防御层；无边界拦截需求用自定义 Filter。
2. **`CONNECT BY` + `LIMIT` 同语句**：wall 校验正常，但 `toSQLString` 输出子句顺序再解析会失败（仅影响 StatFilter mergeSql 展示，不影响拦截判定）。业务罕见，如有此形态反馈后修复输出顺序。
3. **单机实例不支持 DISTRIBUTE BY 执行**：`pgxc_node_name=gs_single` 的实例执行分布式 DDL 报 "distributed capability is not supported" 属预期（wall 解析不受影响）。本地 docker gaussdb-dist 实测为单机形态；客户分布式实例可实际执行。
4. **StatFilter 统计**：gaussdb 未接入 SchemaStatVisitor（表/字段维度统计走默认 visitor），WallFilter 不受影响。
5. **驱动为 provided**：应用需自带 GaussDB JDBC 驱动（compile 依赖 gaussdbjdbc）。

## 九、变更文件清单（相对官方 1.2.5）

**新增**（gaussdb 方言 + wall + vendor，17 个主代码文件）：
- `sql/dialect/gaussdb/`：GaussDbLexer / GaussDbExprParser / GaussDbStatementParser / GaussDbSelectParser / GaussDbCreateTableParser、ast(GaussDbObject/DistributeBy/PartitionValue + stmt:CreateTable/Insert/Vacuum)、visitor(GaussDbASTVisitor/OutputVisitor)
- `wall/spi/`：GaussDBWallProvider / GaussDBWallVisitor
- `util/GaussDBUtils.java`、`pool/vendor/GaussDBValidConnectionChecker.java` / `GaussDBExceptionSorter.java`
- 资源：`META-INF/druid/wall/gaussdb/`（deny-function/deny-table）、`META-INF/druid/parser/gaussdb/keywords`

**修改**（增量式）：pom.xml（驱动依赖 + jdk.tools exclusions）、DbType、Token、Lexer、FnvHash、SQLPartitionValue、SQLCreateTableStatement、WallFilter、WallUtils、SQLParserUtils、SQLUtils、JdbcUtils、JdbcConstants、DruidDataSource、DruidXADataSource

**测试新增**：GaussDBWallTest / GaussDBDenyFunctionTest / GaussDBUpsertTest / GaussDBOracleCompatWallTest / GaussDbResourceTest（+6 资源文件）/ GaussDBIntegrationTest / SQLResourceTest 基类

**2026-10 FVT 整改**（29 个失败检查项清零，详见 `tasks/todo.md`）：
- 主代码：WallProvider（isEOF）、WallVisitorUtils（恒真 4 处条件翻转 + getValue_or + isSimpleConstExpr 升级 + gaussdb 子查询 FROM 豁免撤销 + checkUnion gaussdb 分支 + checkWhereSubQuery + 恒假分支）、WallConfig/WallConfigMBean（selectWhereSubQueryCheck 开关）、ErrorCode（SELECT_WHERE_SUB_QUERY=2114）、PGWallVisitor/SQLServerWallVisitor（补 visit，上游 2767ece28）、GaussDBWallProvider（checkValid 空 SQL 拒绝 + 默认开 subquery 检查）
- 资源：`META-INF/druid/wall/gaussdb/deny-schema.txt`（information_schema）
- 测试：WallStatTestWhereAlwaysTrue（移植+gaussdb 用例）、GaussDBWallDenyTableTest / GaussDBWallInjectionTest / GaussDBWallSqlParseTest / GaussDBWallSubQueryCheckTest（FVT 等价回归 26 项）+ 40 个存量断言类同步上游新版（MySqlWallTest91.test_true4 保留 1.2.5 断言）

---

*配套实机测试包：`D:\workplace\code\Druid\druid-gaussdb-demo`（双击 run.bat，21 项自动检查，README 含运行/判读/自定义拦截说明）。*
