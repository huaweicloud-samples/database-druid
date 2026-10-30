package com.alibaba.druid.bvt.filter.wall.gaussdb;

import com.alibaba.druid.wall.WallUtils;
import junit.framework.TestCase;
import org.junit.Assert;

/**
 * FVT《Druid 1.2.5 GaussDB 集中式测试报告》3.3 等价回归:
 * 空 SQL / 纯注释 SQL 由 GaussDBWallProvider.checkValid 直接拒绝。
 */
public class GaussDBWallSqlParseTest extends TestCase {
    // 3.3#1 空 SQL
    public void test_empty_sql() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB(""));
    }

    // 3.3#2 纯注释 SQL
    public void test_comment_only_sql() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB("-- this is a comment"));
    }

    // 块注释同理
    public void test_block_comment_only_sql() throws Exception {
        Assert.assertFalse(WallUtils.isValidateGaussDB("/* comment only */"));
    }

    // 锚点: 正常 SQL 不受影响
    public void test_normal_sql_still_valid() throws Exception {
        Assert.assertTrue(WallUtils.isValidateGaussDB("SELECT 1"));
        Assert.assertTrue(WallUtils.isValidateGaussDB("SELECT '--'"));
    }
}
