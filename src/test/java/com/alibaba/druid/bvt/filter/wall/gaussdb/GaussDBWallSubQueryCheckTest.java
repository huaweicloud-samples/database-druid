package com.alibaba.druid.bvt.filter.wall.gaussdb;

import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallUtils;
import com.alibaba.druid.wall.spi.GaussDBWallProvider;
import com.alibaba.druid.wall.spi.MySqlWallProvider;
import junit.framework.TestCase;
import org.junit.Assert;

/**
 * selectWhereSubQueryCheck 开关行为:
 * GaussDBWallProvider 默认配置开启; 显式关闭后放行; 其他方言默认关闭不受影响。
 */
public class GaussDBWallSubQueryCheckTest extends TestCase {
    public void test_gaussdb_default_on() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(
                "SELECT * FROM t_users WHERE id IN (SELECT id FROM t_admin)"));
    }

    public void test_gaussdb_explicit_off() throws Exception {
        WallConfig config = new WallConfig(GaussDBWallProvider.DEFAULT_CONFIG_DIR);
        config.setSelectWhereSubQueryCheck(false);
        Assert.assertTrue(WallUtils.isValidateGaussDB(
                "SELECT * FROM t_users WHERE id IN (SELECT id FROM t_admin)", config));
    }

    public void test_other_dialect_default_off() throws Exception {
        // MySQL 默认不开启该检查, 行为与整改前一致
        Assert.assertTrue(WallUtils.isValidateMySql(
                "SELECT * FROM t_users WHERE id IN (SELECT id FROM t_admin)"));
    }
}
