package com.alibaba.druid.bvt.filter.wall.gaussdb;

import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallUtils;
import com.alibaba.druid.wall.spi.GaussDBWallProvider;
import junit.framework.TestCase;
import org.junit.Assert;

/**
 * FVT《Druid 1.2.5 GaussDB 集中式测试报告》3.1 + 3.4 等价回归:
 * deny-table 在子查询 FROM 位置生效(顶层 FROM 豁免为上游统一语义, 保持放行)。
 */
public class GaussDBWallDenyTableTest extends TestCase {
    // 3.1#1 默认配置: pg_database 在 gaussdb deny-table.txt 中
    public void test_deny_subquery_pg_database() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB("SELECT * FROM (SELECT * FROM pg_database) t"));
    }

    // 3.1#2 运行时追加 deny-table
    public void test_deny_subquery_runtime_added() throws Exception {
        WallConfig config = new WallConfig(GaussDBWallProvider.DEFAULT_CONFIG_DIR);
        config.getDenyTables().add("my_secret_table");
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT * FROM (SELECT * FROM my_secret_table) t", config));
    }

    // 3.1#3 / 3.1#4 v$ / v_$ 前缀表名
    public void test_deny_subquery_v_dollar() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB("SELECT * FROM (SELECT * FROM v$session) t"));
        Assert.assertFalse(WallUtils.isValidateGaussDB("SELECT * FROM (SELECT * FROM v_$sesstat) t"));
    }

    // 3.4 blacklist API: provider.check 违规非空
    public void test_blacklist_addDenyTable_check_violations() throws Exception {
        WallConfig config = new WallConfig(GaussDBWallProvider.DEFAULT_CONFIG_DIR);
        config.getDenyTables().add("secret_table");
        GaussDBWallProvider provider = new GaussDBWallProvider(config);
        Assert.assertFalse(provider.check("SELECT * FROM (SELECT * FROM secret_table) t").getViolations().isEmpty());
    }

    // 语义锚点: 顶层 FROM 的 deny 表仍豁免(上游统一语义, GaussDBWallTest1 依赖此行为)
    public void test_top_level_from_still_allowed() throws Exception {
        Assert.assertTrue(WallUtils.isValidateGaussDB(
                "select pg_encoding_to_char(encoding) from pg_database where datname = current_database()"));
    }

    // 语义锚点: WHERE 子查询 deny-table 原本就生效(防本次修改回归)
    public void test_where_in_subquery_deny() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT * FROM t WHERE id IN (SELECT id FROM pg_database)"));
    }
}
