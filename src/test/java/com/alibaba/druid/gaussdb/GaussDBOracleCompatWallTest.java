package com.alibaba.druid.gaussdb;

import com.alibaba.druid.wall.WallUtils;
import junit.framework.TestCase;
import org.junit.Assert;

/**
 * GaussDB Oracle 兼容模式(A)典型语法 × WallFilter 回归测试。
 * 全部语句应通过 wall（isValidateGaussDB = true）。
 *
 * 覆盖：dual/SYSDATE、NVL/DECODE/TO_CHAR/TO_DATE、ROWNUM、(+) 外连接、
 * START WITH/CONNECT BY 层次查询、序列 nextval/currval、FOR UPDATE、
 * LISTAGG WITHIN GROUP、MERGE INTO、ON DUPLICATE KEY UPDATE、
 * CREATE SEQUENCE、CALL、MINUS/INTERSECT。
 */
public class GaussDBOracleCompatWallTest extends TestCase {

    public void test_oracle_compat_syntax_pass_wall() {
        String[] sqls = {
            // 基础
            "select 1 from dual",
            "select sysdate from dual",
            // Oracle 函数
            "select nvl(1, 2) from dual",
            "select decode(1, 1, 'a', 'b') from dual",
            "select to_char(sysdate, 'yyyy-mm-dd') from dual",
            "select to_date('2024-01-01', 'yyyy-mm-dd') from dual",
            // 伪列 / 外连接
            "select * from t where rownum <= 10",
            "select t1.a from t t1, t t2 where t1.id = t2.id(+)",
            // 层次查询
            "select a from t start with id = 1 connect by prior pid = id",
            "select a from t connect by prior pid = id",
            // 序列
            "select seq_test.nextval from dual",
            "select seq_test.currval from dual",
            // 锁
            "select a from t where id = 1 for update",
            // 聚合
            "select listagg(a, ',') within group (order by a) from t",
            "select group_concat(col1 order by submit_time desc separator '|') from t1",
            // A 模式 upsert
            "merge into t1 t using (select 1 as id from dual) s on t.id = s.id when matched then update set t.a = 1 when not matched then insert values (1, 1)",
            "insert into t values (1, 'a') on duplicate key update a = 'b'",
            // 序列 DDL
            "create sequence seq_test2 start with 1 increment by 1",
            // 过程调用
            "call proc_test()",
            // 集合操作
            "select * from t minus select * from t",
            "select a from t intersect select a from t",
        };
        for (String sql : sqls) {
            Assert.assertTrue("wall 应放行(A模式语法): " + sql, WallUtils.isValidateGaussDB(sql));
        }
    }

    public void test_hierarchical_roundtrip() {
        String sql = "select a from t start with id = 1 connect by prior pid = id";
        com.alibaba.druid.sql.ast.SQLStatement stmt =
                new com.alibaba.druid.sql.dialect.gaussdb.parser.GaussDbStatementParser(sql).parseStatement();
        String out = com.alibaba.druid.sql.SQLUtils.toSQLString(stmt, com.alibaba.druid.DbType.gaussdb);
        Assert.assertEquals("SELECT a FROM t START WITH id = 1 CONNECT BY PRIOR pid = id", out.replaceAll("\\s+", " ").trim());
    }

    public void test_listagg_within_group_roundtrip() {
        String sql = "select listagg(a, ',') within group (order by a) from t";
        com.alibaba.druid.sql.ast.SQLStatement stmt =
                new com.alibaba.druid.sql.dialect.gaussdb.parser.GaussDbStatementParser(sql).parseStatement();
        String out = com.alibaba.druid.sql.SQLUtils.toSQLString(stmt, com.alibaba.druid.DbType.gaussdb);
        Assert.assertEquals("SELECT listagg(a, ',') WITHIN GROUP (ORDER BY a) FROM t", out.replaceAll("\\s+", " ").trim());
    }
}
