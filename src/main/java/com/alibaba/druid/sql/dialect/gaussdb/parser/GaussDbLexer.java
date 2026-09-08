package com.alibaba.druid.sql.dialect.gaussdb.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGLexer;
import com.alibaba.druid.sql.parser.Keywords;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.Token;

import java.util.HashMap;
import java.util.Map;

/**
 * 1.2.5 适配版：1.2.5 的 Lexer 无 loadKeywords() 钩子，沿用 PGLexer 的
 * 静态 Keywords + 构造器赋值模式（含 1.2.5 的 keywods 字段拼写）。
 */
public class GaussDbLexer extends PGLexer {

    public final static Keywords DEFAULT_GAUSSDB_KEYWORDS;

    static {
        Map<String, Token> map = new HashMap<String, Token>();

        map.put("DISTRIBUTE", Token.DISTRIBUTE);
        map.put("SET", Token.SET);
        map.put("PARTITION", Token.PARTITION);
        map.put("START", Token.START);
        map.put("PARTIAL", Token.PARTIAL);
        map.put("KEY", Token.KEY);
        map.put("OVERWRITE", Token.OVERWRITE);
        map.put("LOCAL", Token.LOCAL);
        map.put("UNLOGGED", Token.UNLOGGED);
        // Oracle 兼容模式(A)：层次查询
        map.put("CONNECT", Token.CONNECT);
        map.put("PRIOR", Token.PRIOR);

        map.putAll(DEFAULT_PG_KEYWORDS.getKeywords());

        map.remove("LANGUAGE"); // GaussDB does not consider it as a reserved keyword

        DEFAULT_GAUSSDB_KEYWORDS = new Keywords(map);
    }

    public GaussDbLexer(String input, SQLParserFeature... features) {
        super(input, features);
        super.keywods = DEFAULT_GAUSSDB_KEYWORDS;
        super.dbType = DbType.gaussdb;
    }
}
