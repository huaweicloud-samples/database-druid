package com.alibaba.druid.gaussdb;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.vendor.GaussDBValidConnectionChecker;
import com.alibaba.druid.wall.WallFilter;
import junit.framework.TestCase;
import org.junit.Assert;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;

/**
 * 507 分布式真库集成测试（docker gaussdb-dist，Oracle 兼容模式 sql_compatibility=A）。
 * 库不可达时用例自动跳过，不影响 CI。
 *
 * wall 语义说明（与上游 1.2.5/1.2.25 一致，已在 fork 与原生 PG wall 对拍确认）：
 * - deny-function 在 WHERE/HAVING 等条件上下文触发，SELECT 列表中的函数调用不拦；
 * - deny-table 在子查询 FROM 触发，顶层 FROM/UPDATE/DELETE 目标表不拦。
 */
public class GaussDBIntegrationTest extends TestCase {

    private static final String URL = "jdbc:gaussdb://localhost:5432/druid_fvt_test";
    private static final String USER = "appuser";
    private static final String PASSWORD = "App@User456";

    private DruidDataSource dataSource;
    private boolean dbReachable;

    @Override
    protected void setUp() throws Exception {
        dataSource = new DruidDataSource();
        dataSource.setUrl(URL);
        dataSource.setUsername(USER);
        dataSource.setPassword(PASSWORD);
        dataSource.setDriverClassName("com.huawei.gaussdb.jdbc.Driver");
        dataSource.setInitialSize(1);
        dataSource.setMaxActive(4);
        dataSource.setValidationQuery("SELECT 'x'");
        dataSource.setTestWhileIdle(true);

        WallFilter wallFilter = new WallFilter();
        dataSource.setProxyFilters(Collections.singletonList(wallFilter));

        try {
            dataSource.init();
            dbReachable = true;
        } catch (Exception e) {
            dbReachable = false;
            System.out.println("[SKIP] GaussDB 507 不可达: " + e.getMessage());
        }
    }

    @Override
    protected void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private void assumeDbReachable() {
        if (!dbReachable) {
            System.out.println("[SKIP] 数据库不可达，跳过用例: " + getName());
        }
    }

    public void test_01_dbType_detect() {
        if (!dbReachable) { assumeDbReachable(); return; }
        Assert.assertEquals("gaussdb", dataSource.getDbType());
        Assert.assertTrue(dataSource.getValidConnectionChecker() instanceof GaussDBValidConnectionChecker);
        WallFilter wallFilter = (WallFilter) dataSource.getProxyFilters().get(0);
        Assert.assertTrue(wallFilter.getProvider() instanceof com.alibaba.druid.wall.spi.GaussDBWallProvider);
    }

    public void test_02_wall_select_pass() throws Exception {
        if (!dbReachable) { assumeDbReachable(); return; }
        Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select 1 as x");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(1, rs.getInt(1));
        rs.close();
        stmt.close();
        conn.close();
    }

    public void test_03_wall_oracle_compat_sql() throws Exception {
        if (!dbReachable) { assumeDbReachable(); return; }
        Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("drop table if exists druid_gaussdb_it");
        } catch (SQLException ignore) {
            // 表可能不存在
        }
        stmt.execute("create table druid_gaussdb_it (id int primary key, cnt int)");

        // Oracle 兼容模式（A）：dual 查询
        ResultSet rs = stmt.executeQuery("select 1 from dual");
        Assert.assertTrue(rs.next());
        rs.close();

        // A 模式支持的 upsert 语法一：ON DUPLICATE KEY UPDATE（GaussDb 方言专门支持）
        stmt.executeUpdate("insert into druid_gaussdb_it values (1, 1)");
        int affected = stmt.executeUpdate(
                "insert into druid_gaussdb_it values (1, 1) on duplicate key update cnt = cnt + 1");
        Assert.assertTrue(affected >= 1);

        // A 模式支持的 upsert 语法二：MERGE INTO
        affected = stmt.executeUpdate(
                "merge into druid_gaussdb_it t using (select 1 as id from dual) s on t.id = s.id "
                        + "when matched then update set cnt = t.cnt + 1 "
                        + "when not matched then insert values (s.id, 1)");
        Assert.assertTrue(affected >= 1);

        rs = stmt.executeQuery("select cnt from druid_gaussdb_it where id = 1");
        Assert.assertTrue(rs.next());
        Assert.assertEquals(3, rs.getInt(1));
        rs.close();

        stmt.execute("drop table druid_gaussdb_it");
        stmt.close();
        conn.close();
    }

    public void test_04_wall_deny_function_blocked() throws Exception {
        if (!dbReachable) { assumeDbReachable(); return; }
        Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("drop table if exists druid_gaussdb_it2");
        } catch (SQLException ignore) {
        }
        stmt.execute("create table druid_gaussdb_it2 (id int)");
        try {
            // deny-function 在 WHERE 上下文触发（上游语义）
            stmt.executeQuery("select * from druid_gaussdb_it2 where pg_sleep(1) is not null");
            Assert.fail("WHERE 中的 pg_sleep 应被 wall deny-function 拦截");
        } catch (SQLException e) {
            Assert.assertNotNull(e.getMessage());
        } finally {
            try { stmt.execute("drop table druid_gaussdb_it2"); } catch (SQLException ignore) {}
            stmt.close();
            conn.close();
        }
    }

    public void test_05_wall_deny_table_blocked() throws Exception {
        if (!dbReachable) { assumeDbReachable(); return; }
        Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        try {
            stmt.execute("drop table if exists druid_gaussdb_it3");
        } catch (SQLException ignore) {
        }
        stmt.execute("create table druid_gaussdb_it3 (id int)");
        try {
            // deny-table 在子查询 FROM 触发（上游语义）
            stmt.executeQuery("select * from druid_gaussdb_it3 where id in (select oid from pg_database)");
            Assert.fail("子查询中的 pg_database 应被 wall deny-table 拦截");
        } catch (SQLException e) {
            Assert.assertNotNull(e.getMessage());
        } finally {
            try { stmt.execute("drop table druid_gaussdb_it3"); } catch (SQLException ignore) {}
            stmt.close();
            conn.close();
        }
    }

    public void test_06_wall_distributed_ddl_pass() throws Exception {
        if (!dbReachable) { assumeDbReachable(); return; }
        WallFilter wallFilter = (WallFilter) dataSource.getProxyFilters().get(0);
        // 分布式 DDL 仅验证 wall 解析通过（本 docker 实例不支持 DISTRIBUTE BY 的实际执行，
        // 客户分布式环境可执行）
        String[] ddl = {
                "create table t_ddl_wall (id int, v int) distribute by hash (id)",
                "create table t_ddl_wall (id int, v int) with (orientation = column) distribute by hash (id)",
                "create unlogged table t_ddl_wall (id int) distribute by replication"
        };
        for (String sql : ddl) {
            Assert.assertTrue("wall 应放行: " + sql, wallFilter.getProvider().checkValid(sql));
        }
    }
}
