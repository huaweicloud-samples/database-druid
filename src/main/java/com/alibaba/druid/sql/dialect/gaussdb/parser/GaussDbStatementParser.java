package com.alibaba.druid.sql.dialect.gaussdb.parser;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.dialect.gaussdb.ast.stmt.GaussDbInsertStatement;
import com.alibaba.druid.sql.dialect.gaussdb.ast.stmt.GaussDbVacuumStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGSQLStatementParser;
import com.alibaba.druid.sql.parser.EOFParserException;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLCreateTableParser;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.util.FnvHash;

import java.util.ArrayList;
import java.util.List;

public class GaussDbStatementParser extends PGSQLStatementParser {
    public GaussDbStatementParser(String sql) {
        super(new GaussDbExprParser(sql));
    }

    public GaussDbStatementParser(String sql, SQLParserFeature... features) {
        super(new GaussDbExprParser(sql, features));
    }

    public GaussDbCreateTableParser getSQLCreateTableParser() {
        return new GaussDbCreateTableParser(this.exprParser);
    }

    /**
     * 1.2.5 适配：替换为 gaussdb 的 SELECT 解析器（支持 generate_series 等函数表源带列定义列表）。
     */
    @Override
    public GaussDbSelectParser createSQLSelectParser() {
        return new GaussDbSelectParser(this.exprParser, selectListCache);
    }

    @Override
    public SQLCreateTableStatement parseCreateTable() {
        return getSQLCreateTableParser().parseCreateTable();
    }

    /**
     * 1.2.5 适配：基类 parseCreate 不识别 UNLOGGED（会落入 default 抛 TODO），
     * 这里拦截 CREATE UNLOGGED TABLE 并交给 gaussdb 建表解析器。
     */
    @Override
    public SQLStatement parseCreate() {
        char markChar = lexer.current();
        int markBp = lexer.bp();

        accept(Token.CREATE);

        boolean gaussTableType = lexer.token() == Token.UNLOGGED || lexer.identifierEquals("UNLOGGED")
                || lexer.token() == Token.LOCAL
                || lexer.identifierEquals("TEMP");
        if (gaussTableType) {
            lexer.reset(markBp, markChar, Token.CREATE);
            SQLCreateTableParser createTableParser = getSQLCreateTableParser();
            return createTableParser.parseCreateTable();
        }

        lexer.reset(markBp, markChar, Token.CREATE);
        return super.parseCreate();
    }

    /**
     * 1.2.5 适配：PG 方言无 VACUUM 语句，gaussdb 需要（含 DELTAMERGE/HDFSDIRECTORY 运维语法）。
     */
    @Override
    public boolean parseStatementListDialect(List<SQLStatement> statementList) {
        if (lexer.token() == Token.IDENTIFIER && "VACUUM".equalsIgnoreCase(lexer.stringVal())) {
            statementList.add(parseVacuumTable());
            return true;
        }
        return super.parseStatementListDialect(statementList);
    }

    public GaussDbVacuumStatement parseVacuumTable() {
        GaussDbVacuumStatement stmt = new GaussDbVacuumStatement(this.dbType);
        acceptIdentifier("VACUUM");
        Lexer.SavePoint mark = lexer.mark();
        String strVal = lexer.stringVal();
        for (; ; ) {
            if (Token.SEMI.equals(lexer.token())) {
                stmt.setAfterSemi(true);
                return stmt;
            }
            if (lexer.isEOF()) {
                lexer.nextToken();
                return stmt;
            }
            if (strVal.equalsIgnoreCase("FULL")) {
                stmt.setFull(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("FREEZE")) {
                stmt.setFreeze(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("VERBOSE")) {
                stmt.setVerbose(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("ANALYZE")) {
                stmt.setAnalyze(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("DISABLE_PAGE_SKIPPING")) {
                stmt.setDisablePageSkipping(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("SKIP_LOCKED")) {
                stmt.setSkipLocked(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("PROCESS_TOAST")) {
                stmt.setProcessToast(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("TRUNCATE")) {
                stmt.setTruncate(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("DELTAMERGE")) { // gaussDb
                stmt.setDeltaMerge(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else if (strVal.equalsIgnoreCase("HDFSDIRECTORY")) { // gaussDb
                stmt.setHdfsDirectory(true);
                lexer.nextToken();
                mark = lexer.mark();
                strVal = lexer.stringVal();
                continue;
            } else {
                lexer.reset(mark);
                break;
            }
        }
        List<SQLName> names = new ArrayList<SQLName>();
        this.exprParser.names(names, stmt);
        for (SQLName name : names) {
            SQLExprTableSource sqlExprTableSource = new SQLExprTableSource(name);
            sqlExprTableSource.setParent(stmt);
            stmt.getTableSources().add(sqlExprTableSource);
        }
        return stmt;
    }

    /**
     * 1.2.5 适配：1.2.5 的 parseStatement 不吞尾部分号，gaussdb 语句常以分号结尾。
     * 吞掉时按基类惯例标记 afterSemi，使输出侧还原分号。
     */
    @Override
    public SQLStatement parseStatement() {
        SQLStatement stmt = super.parseStatement();
        if (stmt != null && lexer.token() == Token.SEMI) {
            lexer.nextToken();
            stmt.setAfterSemi(true);
        }
        return stmt;
    }

    @Override
    public PGInsertStatement parseInsert() {
        GaussDbInsertStatement stmt = new GaussDbInsertStatement();

        if (lexer.token() == Token.INSERT) {
            lexer.nextToken();
            if (lexer.identifierEquals("IGNORE")) {
                lexer.nextToken();
                stmt.setIgnore(true);
            } else if (lexer.token() == Token.OVERWRITE) {
                lexer.nextToken();
                stmt.setOverwrite(true);
            }
            accept(Token.INTO);

            stmt.setTableSource(this.exprParser.name());
            if (lexer.token() == Token.AS) {
                lexer.nextToken();
                stmt.setAlias(lexer.stringVal());
                lexer.nextToken();
            } else if (lexer.token() == Token.IDENTIFIER) {
                stmt.setAlias(lexer.stringVal());
                lexer.nextToken();
            }

        }

        if (lexer.nextIf(Token.LPAREN)) {
            this.exprParser.exprList(stmt.getColumns(), stmt);
            accept(Token.RPAREN);
        }

        if (lexer.nextIf(Token.DEFAULT)) {
            accept(Token.VALUES);
            stmt.setDefaultValues(true);
        }

        if (lexer.nextIf(Token.VALUES)) {
            for (; ; ) {
                accept(Token.LPAREN);
                SQLInsertStatement.ValuesClause valuesCaluse = new SQLInsertStatement.ValuesClause();
                this.exprParser.exprList(valuesCaluse.getValues(), valuesCaluse);
                stmt.addValueCause(valuesCaluse);

                accept(Token.RPAREN);
                if (lexer.nextIf(Token.COMMA)) {
                    continue;
                }
                break;
            }
        } else if (lexer.token() == (Token.SELECT)) {
            SQLSelect select = this.createSQLSelectParser().select();
            stmt.setQuery(select);
        }

        if (lexer.nextIf(Token.ON)) {
            if (lexer.identifierEquals(FnvHash.Constants.CONFLICT)) {
                lexer.nextToken();

                if (lexer.nextIf(Token.LPAREN)) {
                    List<SQLExpr> onConflictTarget = new ArrayList<SQLExpr>();
                    this.exprParser.exprList(onConflictTarget, stmt);
                    stmt.setOnConflictTarget(onConflictTarget);
                    accept(Token.RPAREN);
                }

                if (lexer.nextIf(Token.ON)) {
                    accept(Token.CONSTRAINT);
                    SQLName constraintName = this.exprParser.name();
                    stmt.setOnConflictConstraint(constraintName);
                }

                if (lexer.nextIf(Token.WHERE)) {
                    SQLExpr where = this.exprParser.expr();
                    stmt.setOnConflictWhere(where);
                }

                if (lexer.nextIf(Token.DO)) {
                    if (lexer.identifierEquals(FnvHash.Constants.NOTHING)) {
                        lexer.nextToken();
                        stmt.setOnConflictDoNothing(true);
                    } else {
                        accept(Token.UPDATE);
                        accept(Token.SET);

                        for (; ; ) {
                            SQLUpdateSetItem item = this.exprParser.parseUpdateSetItem();
                            stmt.addConflicUpdateItem(item);

                            if (lexer.token() != Token.COMMA) {
                                break;
                            }

                            lexer.nextToken();
                        }
                        if (lexer.nextIf(Token.WHERE)) {
                            SQLExpr where = this.exprParser.expr();
                            stmt.setOnConflictUpdateWhere(where);
                        }
                    }
                }
            } else if (lexer.identifierEquals("DUPLICATE")) {
                lexer.nextToken();
                accept(Token.KEY);
                accept(Token.UPDATE);

                List<SQLExpr> duplicateKeyUpdate = stmt.getDuplicateKeyUpdate();
                for (; ; ) {
                    SQLName name = this.exprParser.name();
                    accept(Token.EQ);
                    SQLExpr value;
                    try {
                        value = this.exprParser.expr();
                    } catch (EOFParserException e) {
                        throw new ParserException("EOF, " + name + "=", e);
                    }

                    SQLBinaryOpExpr assignment = new SQLBinaryOpExpr(name, SQLBinaryOperator.Equality, value);
                    assignment.setParent(stmt);
                    duplicateKeyUpdate.add(assignment);

                    if (lexer.token() == Token.COMMA) {
                        lexer.nextTokenIdent();
                        continue;
                    }
                    break;
                }
            }
        }

        if (lexer.nextIf(Token.RETURNING)) {
            SQLExpr returning = this.exprParser.expr();

            if (lexer.nextIf(Token.COMMA)) {
                SQLListExpr list = new SQLListExpr();
                list.addItem(returning);

                this.exprParser.exprList(list.getItems(), list);

                returning = list;
            }

            stmt.setReturning(returning);
        }
        return stmt;
    }
}
