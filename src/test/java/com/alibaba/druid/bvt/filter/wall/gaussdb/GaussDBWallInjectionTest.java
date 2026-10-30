package com.alibaba.druid.bvt.filter.wall.gaussdb;

import com.alibaba.druid.wall.WallUtils;
import junit.framework.TestCase;
import org.junit.Assert;

/**
 * FVT《Druid 1.2.5 GaussDB 集中式测试报告》3.2 等价回归:
 * 恒真/恒假条件、UNION/UNION ALL 注入、information_schema、WHERE 子查询检测。
 * 全部使用默认配置(isValidateGaussDB(sql) => GaussDBWallProvider 无参构造)。
 */
public class GaussDBWallInjectionTest extends TestCase {
    // 3.2#1 WHERE 恒真
    public void test_where_alway_true() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB("SELECT * FROM t_users WHERE 1=1"));
    }

    // 3.2#4 WHERE 恒假(gaussdb 增强)
    public void test_where_alway_false() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB("SELECT * FROM t_users WHERE 1=2"));
    }

    // 3.2#2 UNION 注入
    public void test_union_select() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT id FROM t_users UNION SELECT password FROM t_passwords"));
    }

    // UNION ALL 同样拦截(决策项: union 注入旁路不保留)
    public void test_union_all_select() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT id FROM t_users UNION ALL SELECT password FROM t_passwords"));
    }

    // 3.2#3 information_schema 子查询(deny-schema + 子查询内 schema 检查)
    public void test_information_schema_subquery() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT * FROM (SELECT * FROM information_schema.tables) t"));
    }

    // 3.2#5 WHERE 嵌套子查询(selectWhereSubQueryCheck 默认开启)
    public void test_where_in_subquery() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT * FROM t_users WHERE id IN (SELECT id FROM t_admin)"));
    }

    // 3.2#6 UNION + information_schema(双重机制)
    public void test_union_information_schema() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT id FROM t_users UNION SELECT table_name FROM information_schema.tables"));
    }
}
