package com.alibaba.druid.sql.dialect.gaussdb.parser;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGSelectParser;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.SQLExprParser;
import com.alibaba.druid.sql.parser.SQLSelectListCache;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.util.FnvHash;

/**
 * 1.2.5 适配：PG 函数表源 generate_series(...) s(daytime)（无 AS、带列定义列表）
 * 在 1.2.5 基类无法解析，此处前置于 parseTableSource 处理。
 * AST 复用 SQLExprTableSource（expr + alias + columns），输出为 expr AS alias (cols)。
 */
public class GaussDbSelectParser extends PGSelectParser {

    public GaussDbSelectParser(SQLExprParser exprParser) {
        super(exprParser);
    }

    public GaussDbSelectParser(SQLExprParser exprParser, SQLSelectListCache selectListCache) {
        super(exprParser, selectListCache);
    }

    public GaussDbSelectParser(String sql) {
        super(sql);
    }

    @Override
    public SQLTableSource parseTableSource() {
        SQLTableSource functionTableSource = parseFunctionTableSource();
        if (functionTableSource != null) {
            return parseTableSourceRest(functionTableSource);
        }
        return super.parseTableSource();
    }

    /**
     * Oracle 兼容模式(A)：层次查询 START WITH ... CONNECT BY ...（位于 WHERE 之后）。
     */
    @Override
    public void parseWhere(com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock queryBlock) {
        super.parseWhere(queryBlock);

        if (lexer.token() == Token.START && isStartWithClause()) {
            lexer.nextToken();
            accept(Token.WITH);
            queryBlock.setStartWith(this.exprParser.expr());
        }

        if (lexer.token() == Token.CONNECT) {
            lexer.nextToken();
            accept(Token.BY);
            queryBlock.setConnectBy(this.exprParser.expr());
        }
    }

    /**
     * 区分层次查询的 "START WITH" 与事务语句 "START TRANSACTION/BEGIN"：
     * 向前看一个 token，WITH 之后是条件表达式而非事务关键字。
     */
    private boolean isStartWithClause() {
        Lexer.SavePoint mark = lexer.mark();
        lexer.nextToken(); // START
        boolean isWith = lexer.token() == Token.WITH;
        lexer.reset(mark);
        return isWith;
    }

    /**
     * 1.2.5 适配：基类仅支持 GROUP BY ROLLUP(...) 前置写法，
     * gaussdb 常见 "GROUP BY 1, 2, rollup(col)" 后置项写法在此解析为方法调用。
     */
    @Override
    protected SQLExpr parseGroupByItem() {
        if (lexer.identifierEquals(FnvHash.Constants.ROLLUP)
                || lexer.identifierEquals(FnvHash.Constants.CUBE)) {
            SQLMethodInvokeExpr func = new SQLMethodInvokeExpr(lexer.stringVal());
            lexer.nextToken();
            if (lexer.token() == Token.LPAREN) {
                lexer.nextToken();
                for (; ; ) {
                    if (lexer.token() == Token.RPAREN) {
                        break;
                    }

                    SQLExpr expr;
                    if (lexer.token() == Token.LPAREN) {
                        accept(Token.LPAREN);
                        SQLListExpr list = new SQLListExpr();
                        if (lexer.token() == Token.COMMA) {
                            lexer.nextToken();
                        }
                        this.exprParser.exprList(list.getItems(), list);
                        accept(Token.RPAREN);
                        expr = list;
                    } else {
                        expr = this.exprParser.expr();
                    }
                    func.addArgument(expr);

                    if (lexer.token() == Token.COMMA) {
                        lexer.nextToken();
                        continue;
                    }
                    break;
                }
                accept(Token.RPAREN);
            }
            return func;
        }
        return super.parseGroupByItem();
    }

    /**
     * gaussdb 函数表源：func(args) [AS] alias(col, ...)。
     * 覆盖 generate_series / generate_subscripts 等返回集函数；AST 复用
     * SQLExprTableSource（expr + alias + columns），输出为 expr AS alias (cols)。
     */
    protected SQLTableSource parseFunctionTableSource() {
        if (lexer.token() == Token.IDENTIFIER) {
            Lexer.SavePoint mark = lexer.mark();
            String funcName = lexer.stringVal();
            lexer.nextToken();

            if (lexer.token() == Token.LPAREN) {
                lexer.reset(mark);

                SQLExprTableSource tableSource = new SQLExprTableSource();
                SQLExpr expr = this.exprParser.expr();
                // 与上游 SQLGeneratedTableSource 行为一致：返回集函数名规范化为大写
                if (expr instanceof SQLMethodInvokeExpr) {
                    ((SQLMethodInvokeExpr) expr).setMethodName(funcName.toUpperCase());
                }
                tableSource.setExpr(expr);

                if (lexer.token() == Token.AS) {
                    lexer.nextToken();
                }
                String alias = this.tableAlias();
                if (alias != null) {
                    tableSource.setAlias(alias);
                }

                if (lexer.token() == Token.LPAREN) {
                    lexer.nextToken();
                    this.exprParser.names(tableSource.getColumns(), tableSource);
                    accept(Token.RPAREN);
                }

                return tableSource;
            }

            lexer.reset(mark);
        }
        return null;
    }
}
