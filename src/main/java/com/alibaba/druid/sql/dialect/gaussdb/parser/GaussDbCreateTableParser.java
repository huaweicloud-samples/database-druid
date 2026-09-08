package com.alibaba.druid.sql.dialect.gaussdb.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.gaussdb.ast.GaussDbDistributeBy;
import com.alibaba.druid.sql.dialect.gaussdb.ast.stmt.GaussDbCreateTableStatement;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGCreateTableParser;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.parser.SQLExprParser;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.util.FnvHash;

/**
 * 1.2.5 适配版：1.2.5 的 SQLCreateTableParser.parseCreateTable 是单体方法（无
 * createTableBefore/parseCreateTableRest/createTableBodyItem 钩子），因此在此
 * 提供自包含的 parseCreateTable 实现， gaussdb 扩展语法插桩在对应位置。
 */
public class GaussDbCreateTableParser extends PGCreateTableParser {

    public GaussDbCreateTableParser(String sql) {
        super(new GaussDbExprParser(sql));
        dbType = DbType.gaussdb;
    }

    public GaussDbCreateTableParser(SQLExprParser exprParser) {
        super(exprParser);
        dbType = DbType.gaussdb;
    }

    protected SQLCreateTableStatement newCreateStatement() {
        return new GaussDbCreateTableStatement();
    }

    private GaussDbExprParser getGaussDbExprParser() {
        return (GaussDbExprParser) this.exprParser;
    }

    @Override
    public SQLCreateTableStatement parseCreateTable() {
        return parseCreateTable(true);
    }

    public SQLCreateTableStatement parseCreateTable(boolean acceptCreate) {
        GaussDbCreateTableStatement createTable = new GaussDbCreateTableStatement();
        createTable.setDbType(getDbType());

        if (acceptCreate) {
            if (lexer.hasComment() && lexer.isKeepComments()) {
                createTable.addBeforeComment(lexer.readAndResetComments());
            }

            accept(Token.CREATE);
        }

        createTableBefore(createTable);

        if (lexer.token() == Token.TABLE || lexer.identifierEquals("TABLE")) {
            lexer.nextToken();
        } else {
            throw new ParserException("syntax error " + lexer.info());
        }

        if (lexer.token() == Token.IF) {
            lexer.nextToken();
            accept(Token.NOT);
            accept(Token.EXISTS);

            createTable.setIfNotExiists(true);
        }

        createTable.setName(this.exprParser.name());

        if (lexer.token() == Token.LPAREN) {
            lexer.nextToken();

            for (; ; ) {
                createTableBodyItem(createTable);

                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();

                    if (lexer.token() == Token.RPAREN) { // compatible for sql server
                        break;
                    }
                    continue;
                }

                break;
            }

            accept(Token.RPAREN);

            if (lexer.identifierEquals(FnvHash.Constants.INHERITS)) {
                lexer.nextToken();
                accept(Token.LPAREN);
                SQLName inherits = this.exprParser.name();
                createTable.setInherits(new SQLExprTableSource(inherits));
                accept(Token.RPAREN);
            }
        }

        parseCreateTableRest(createTable);

        if (lexer.token() == Token.AS) {
            lexer.nextToken();

            SQLSelect select = this.createSQLSelectParser().select();
            createTable.setSelect(select);
        }

        return createTable;
    }

    protected void createTableBefore(SQLCreateTableStatement createTable) {
        parseTableType(createTable);
        parseTableType(createTable);
    }

    private void parseTableType(SQLCreateTableStatement createTable) {
        if (lexer.nextIf(Token.UNLOGGED)) {
            createTable.config(SQLCreateTableStatement.Feature.Unlogged);
        } else if (lexer.nextIfIdentifier(FnvHash.Constants.GLOBAL)) {
            createTable.config(SQLCreateTableStatement.Feature.Global);
        } else if (lexer.nextIfIdentifier(FnvHash.Constants.TEMPORARY) || lexer.nextIfIdentifier("TEMP")) {
            createTable.config(SQLCreateTableStatement.Feature.Temporary);
        } else if (lexer.nextIf(Token.LOCAL)) {
            createTable.config(SQLCreateTableStatement.Feature.Local);
        }
    }

    protected void createTableBodyItem(SQLCreateTableStatement createTable) {
        Token token = lexer.token();
        if (token == Token.PARTIAL) {
            lexer.nextToken();
            if (lexer.nextIfIdentifier(FnvHash.Constants.CLUSTER)) {
                accept(Token.KEY);
                accept(Token.LPAREN);
                for (; ; ) {
                    SQLSelectOrderByItem item = this.exprParser.parseSelectOrderByItem();
                    createTable.addClusteredByItem(item);
                    if (lexer.token() == Token.COMMA) {
                        lexer.nextToken();
                        continue;
                    } else if (lexer.token() == Token.RPAREN) {
                        accept(Token.RPAREN);
                        break;
                    }
                }
            }
        } else if (token == Token.IDENTIFIER //
                || token == Token.LITERAL_ALIAS) {
            SQLColumnDefinition column = this.exprParser.parseColumn(createTable);
            column.setParent(createTable);
            createTable.getTableElementList().add(column);
        } else if (token == Token.PRIMARY //
                || token == Token.UNIQUE //
                || token == Token.CHECK //
                || token == Token.CONSTRAINT
                || token == Token.FOREIGN) {
            SQLConstraint constraint = this.exprParser.parseConstaint();
            constraint.setParent(createTable);
            createTable.getTableElementList().add((SQLTableElement) constraint);
        } else if (token == Token.TABLESPACE) {
            throw new ParserException("TODO " + lexer.info());
        } else {
            SQLColumnDefinition column = this.exprParser.parseColumn();
            createTable.getTableElementList().add(column);
        }
    }

    protected void parseCreateTableRest(SQLCreateTableStatement stmt) {
        GaussDbCreateTableStatement gdStmt = (GaussDbCreateTableStatement) stmt;
        if (lexer.token() == Token.WITH) {
            lexer.nextToken();
            accept(Token.LPAREN);
            parseAssignItems(gdStmt.getTableOptions(), gdStmt, false);
            accept(Token.RPAREN);
        }
        if (lexer.nextIf(Token.ON)) {
            if (lexer.nextIfIdentifier(FnvHash.Constants.COMMIT)) {
                // 1.2.5 适配：exprParser.name() 不接受 DELETE/PRESERVE 关键字 token，直接构造标识符
                if (lexer.identifierEquals("PRESERVE")) {
                    gdStmt.setOnCommitExpr(new SQLIdentifierExpr(lexer.stringVal()));
                    lexer.nextToken();
                    accept(Token.ROWS);
                } else if (lexer.token() == Token.DELETE) {
                    gdStmt.setOnCommitExpr(new SQLIdentifierExpr("DELETE"));
                    lexer.nextToken();
                    accept(Token.ROWS);
                }
            }
        }
        if (lexer.identifierEquals("COMPRESS") || lexer.identifierEquals("NOCOMPRESS")) {
            gdStmt.setCompressType(exprParser.name());
        }
        GaussDbDistributeBy distributeByClause = parseDistributeBy();
        if (distributeByClause != null) {
            gdStmt.setDistributeBy(distributeByClause);
        }

        if (lexer.nextIf(Token.TO)) {
            if (lexer.nextIf(Token.GROUP)) {
                SQLExpr group = this.exprParser.expr();
                gdStmt.setToGroup(group);
            }
            if (lexer.nextIfIdentifier(FnvHash.Constants.NODE)) {
                SQLExpr node = this.exprParser.expr();
                gdStmt.setToNode(node);
            }
        }

        SQLPartitionBy partitionClause = parsePartitionBy();
        if (partitionClause != null) {
            gdStmt.setPartitioning(partitionClause);
        }

        parseRowMovement(gdStmt);

        if (lexer.nextIf(Token.COMMENT)) {
            lexer.nextIf(Token.EQ);
            SQLExpr comment = this.exprParser.expr();
            gdStmt.setComment(comment);
        }
    }

    public void parseRowMovement(GaussDbCreateTableStatement stmt) {
        if (lexer.token() == Token.ENABLE || lexer.token() == Token.DISABLE) {
            stmt.setRowMovementType(exprParser.name());
            accept(Token.ROW);
            acceptIdentifier("MOVEMENT");
        }
    }

    public SQLPartitionBy parsePartitionBy() {
        if (lexer.nextIf(Token.PARTITION)) {
            accept(Token.BY);
            if (lexer.nextIfIdentifier(FnvHash.Constants.HASH)) {
                SQLPartitionBy hashPartition = new SQLPartitionByHash();
                if (lexer.nextIf(Token.LPAREN)) {
                    if (lexer.token() != Token.IDENTIFIER) {
                        throw new ParserException("expect identifier. " + lexer.info());
                    }
                    for (; ; ) {
                        hashPartition.addColumn(this.exprParser.name());
                        if (lexer.token() == Token.COMMA) {
                            lexer.nextToken();
                            continue;
                        }
                        break;
                    }
                    accept(Token.RPAREN);
                    return hashPartition;
                }
            } else if (lexer.nextIfIdentifier(FnvHash.Constants.RANGE)) {
                return partitionByRange();
            } else if (lexer.nextIfIdentifier(FnvHash.Constants.LIST)) {
                return partitionByList();
            }
        }
        return null;
    }

    protected SQLPartitionByRange partitionByRange() {
        SQLPartitionByRange rangePartition = new SQLPartitionByRange();
        accept(Token.LPAREN);
        for (; ; ) {
            rangePartition.addColumn(this.exprParser.name());
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.RPAREN);
        accept(Token.LPAREN);
        for (; ; ) {
            rangePartition.addPartition(this.getGaussDbExprParser().parsePartition());
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.RPAREN);
        return rangePartition;
    }

    private SQLPartitionByList partitionByList() {
        SQLPartitionByList listPartition = new SQLPartitionByList();
        accept(Token.LPAREN);
        for (; ; ) {
            listPartition.addColumn(this.exprParser.name());
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.RPAREN);
        accept(Token.LPAREN);
        for (; ; ) {
            listPartition.addPartition(this.getGaussDbExprParser().parsePartition());
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.RPAREN);
        return listPartition;
    }

    public GaussDbDistributeBy parseDistributeBy() {
        if (lexer.token() == Token.DISTRIBUTE) {
            lexer.nextToken();
            accept(Token.BY);
            GaussDbDistributeBy distributeBy = new GaussDbDistributeBy();
            if (lexer.identifierEquals(FnvHash.Constants.HASH)) {
                distributeBy.setType(this.exprParser.name());
                if (lexer.nextIf(Token.LPAREN)) {
                    for (; ; ) {
                        distributeBy.addColumn(this.exprParser.name());
                        if (lexer.token() == Token.COMMA) {
                            lexer.nextToken();
                            continue;
                        }
                        break;
                    }
                    accept(Token.RPAREN);
                    return distributeBy;
                }
            } else if (lexer.identifierEquals(FnvHash.Constants.ROUNDROBIN)) {
                distributeBy.setType(this.exprParser.name());
                return distributeBy;
            } else if (lexer.identifierEquals(FnvHash.Constants.REPLICATION)) {
                distributeBy.setType(this.exprParser.name());
                return distributeBy;
            }
        }
        return null;
    }

    public GaussDbDistributeBy distributionByContent(GaussDbDistributeBy distributeBy) {
        accept(Token.LPAREN);
        for (; ; ) {
            distributeBy.addColumn(this.exprParser.name());
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.LPAREN);
        for (; ; ) {
            distributeBy.addDistribution(this.getGaussDbExprParser().parseDistribution());
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                continue;
            }
            break;
        }
        accept(Token.RPAREN);
        return distributeBy;
    }
}
