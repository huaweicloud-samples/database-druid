# druid · GaussDB 适配版

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![Upstream](https://img.shields.io/badge/upstream-alibaba%2Fdruid%201.2.5-blue)](https://github.com/alibaba/druid)

基于 [alibaba/druid](https://github.com/alibaba/druid) **1.2.5** 基线的 GaussDB 适配分支,由 huaweicloud-samples 维护。让 Druid 连接池、WallFilter(SQL 防火墙)与 SQL 解析完整支持 GaussDB(集中式 / 分布式,Oracle 兼容 A 模式)。

> 上游原生不支持 GaussDB 方言(1.2.23+ 仅做 `jdbc:gaussdb:` URL 识别,WallFilter 走 PG 规则兜底)。本分支补齐全链路,并已通过客户 FVT 验收。

## 本分支工作内容

| 模块 | 内容 |
|---|---|
| **SQL 方言** | gaussdb 方言全套:Lexer / Parser(DDL 含 DISTRIBUTE BY / PARTITION BY / UNLOGGED / TEMP、DML 含 INSERT OVERWRITE / MERGE INTO / ON DUPLICATE KEY / generate_series / rollup / cube),Oracle 兼容 A 模式语法(CONNECT BY / LISTAGG WITHIN GROUP / PRIOR / NVL2 等) |
| **WallFilter** | `GaussDBWallProvider` / `GaussDBWallVisitor`(独立方言解析,非 PG 兜底)+ deny-function / deny-table / deny-schema 黑名单资源;FVT 整改:恒真条件修复(上游 2767ece28 移植)、子查询 FROM 拦 deny 表、UNION 注入拦截、恒假条件、WHERE 子查询检测、空 SQL 拒绝 |
| **连接池** | URL / 驱动自动识别 → `GaussDBValidConnectionChecker` / `GaussDBExceptionSorter` 自动挂载 |
| **验收** | 客户 FVT:194 项原有检查零回归,29 项失败项 + issue #2 已修复(gaussdb 拦截语义见 [USAGE.md 第五节](USAGE.md#五wallfilter-配置)) |

## 构建

环境:JDK 8+,Maven 3.x。GaussDB JDBC 驱动为 `provided`(不打进 jar),首次构建前需装入本地 Maven 仓,详见 [USAGE.md 第二节](USAGE.md#二构建)。

```bash
mvn package -DskipTests          # 产物 target/druid-1.2.5.jar
mvn install                      # 装本地仓
```

产物坐标仍为 `com.alibaba:druid:1.2.5`——**可直接替换官方 jar,应用代码零改动**。

## 使用方式

```java
DruidDataSource ds = new DruidDataSource();
ds.setUrl("jdbc:gaussdb://host:port/db");        // jdbc:gaussdb: / jdbc:opengauss: 自动识别
ds.setDriverClassName("com.huawei.gaussdb.jdbc.Driver");

WallFilter wallFilter = new WallFilter();         // 自动路由 GaussDBWallProvider
ds.setProxyFilters(Collections.singletonList(wallFilter));
ds.init();
```

- **方式 A(推荐)**:替换应用中 `com.alibaba:druid:1.2.5` 依赖,包名类名完全不变
- **方式 B**:Spring Boot(druid-spring-boot-starter 1.2.5)配置不变,详见 [USAGE.md 第三节](USAGE.md#三接入使用)
- WallFilter / deny 配置 / 自定义拦截 / 灰度建议:详见 [USAGE.md 第五节](USAGE.md#五wallfilter-配置)

## 测试

```bash
mvn test                                                    # 默认套件(bvt/**, 全量)
mvn test "-Dtest=com.alibaba.druid.bvt.filter.wall.**"     # wall 回归(588 用例)
mvn test -Dtest=GaussDBIntegrationTest                     # 真库集成测试(需本地 GaussDB)
```

- **解析 / wall 回归**:无需数据库,`GaussDBWallDenyTableTest` / `GaussDBWallInjectionTest` / `GaussDBWallSqlParseTest` / `GaussDBWallSubQueryCheckTest` 覆盖 FVT 全部检查点
- **真库集成**:`GaussDBIntegrationTest` 默认连 `jdbc:gaussdb://localhost:5432/druid_fvt_test`,库不可达自动跳过;连接参数在测试类头部常量中调整
- **回归基线**:全量 6183 用例与上游 1.2.5 基线逐类对照零回归(存量 pool 等环境依赖失败与本分支无关)

## 注意事项

1. **驱动 provided**:应用需自带 GaussDB JDBC 驱动(`com.huawei.gaussdb.jdbc.Driver`),版本与数据库匹配即可
2. **wall 拦截语义(gaussdb 增强)**:UNION 拦截 / UNION ALL 放行(递归 CTE 必需语法);`WHERE 1=1`、`WHERE 1=2`、子查询 FROM 的 deny 表、WHERE 子查询(新开关 `selectWhereSubQueryCheck`,gaussdb 默认开)默认拦截;顶层 `FROM pg_database` 等仍放行——完整语义见 [USAGE.md 第五节](USAGE.md#五wallfilter-配置)与[第八节边界说明](USAGE.md#八已知边界与注意事项)
3. **灰度建议**:上线初期 `setLogViolation(true)` + `setThrowException(false)` 观察无误报后再开拦截
4. **黑名单扩展**:应用侧 classpath 放同名 `deny-function.txt` 等即可追加,jar 内默认合并生效
5. **上游功能不受影响**:mysql / oracle / pg 等其他方言行为与官方 1.2.5 一致,gaussdb 增强全部限定在 `DbType.gaussdb`

## 相关文档

- [USAGE.md](USAGE.md) — 完整使用文档(构建 / 接入 / WallFilter 配置 / 测试 / 边界)
- [上游 alibaba/druid](https://github.com/alibaba/druid) — 源项目(Wiki / FAQ)
- [Issue 反馈](https://github.com/huaweicloud-samples/database-druid/issues)

## License

[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.html),与上游一致。
