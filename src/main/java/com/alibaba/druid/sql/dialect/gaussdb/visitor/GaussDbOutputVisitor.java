package com.alibaba.druid.sql.dialect.gaussdb.visitor;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.SQLPartition;
import com.alibaba.druid.sql.ast.SQLPartitionBy;
import com.alibaba.druid.sql.ast.SQLPartitionByList;
import com.alibaba.druid.sql.ast.SQLPartitionByRange;
import com.alibaba.druid.sql.ast.SQLPartitionValue;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLAssignItem;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.gaussdb.ast.GaussDbDistributeBy;
import com.alibaba.druid.sql.dialect.gaussdb.ast.GaussDbPartitionValue;
import com.alibaba.druid.sql.dialect.gaussdb.ast.stmt.GaussDbCreateTableStatement;
import com.alibaba.druid.sql.dialect.gaussdb.ast.stmt.GaussDbInsertStatement;
import com.alibaba.druid.sql.dialect.gaussdb.ast.stmt.GaussDbVacuumStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGInsertStatement;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGOutputVisitor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 1.2.5 适配版：
 * - printCreateTableFeatures/printTableOptions/printTableElement 等辅助方法为本地实现
 *   （1.2.5 的 SQLASTOutputVisitor 尚无这些钩子）；
 * - SQLPartitionSingle 在 1.2.5 不存在，分区/分布值统一使用 SQLPartition；
 * - isIfNotExiists 沿用 1.2.5 拼写。
 */
public class GaussDbOutputVisitor extends PGOutputVisitor implements GaussDbASTVisitor {
    public GaussDbOutputVisitor(Appendable appender, boolean parameterized) {
        super(appender, parameterized);
        dbType = DbType.gaussdb;
    }

    public GaussDbOutputVisitor(Appendable appender) {
        super(appender);
        dbType = DbType.gaussdb;
    }

    @Override
    public boolean visit(GaussDbCreateTableStatement x) {
        print0(ucase ? "CREATE " : "create ");

        printCreateTableFeatures(x);

        print0(ucase ? "TABLE " : "table ");

        if (x.isIfNotExists()) {
            print0(ucase ? "IF NOT EXISTS " : "if not exists ");
        }

        printTableSourceExpr(
                x.getTableSource()
                        .getExpr());

        printTableElements(x);

        printTableOptions(x);

        printOnCommit(x);

        printCompressType(x);

        printDistributeBy(x);

        printToGroup(x);

        printToNode(x);

        printPartitionBy(x);

        printRowMovement(x);

        printCommentExpr(x.getComment());
        return false;
    }

    protected void printCreateTableFeatures(SQLCreateTableStatement x) {
        SQLCreateTableStatement.Feature[] features = {
                SQLCreateTableStatement.Feature.Global,
                SQLCreateTableStatement.Feature.Local,
                SQLCreateTableStatement.Feature.Temporary,
                SQLCreateTableStatement.Feature.Unlogged
        };

        for (SQLCreateTableStatement.Feature feature : features) {
            if (x.isEnabled(feature)) {
                String name = feature.name();
                print0(ucase ? name.toUpperCase() : name.toLowerCase());
                print(' ');
            }
        }
    }

    public void printRowMovement(GaussDbCreateTableStatement x) {
        if (x.getRowMovementType() != null) {
            println();
            x.getRowMovementType().accept(this);
            print(ucase ? " ROW MOVEMENT" : " row movement");
        }
    }

    public void printCompressType(GaussDbCreateTableStatement x) {
        if (x.getCompressType() != null) {
            println();
            x.getCompressType().accept(this);
        }
    }

    public void printOnCommit(GaussDbCreateTableStatement x) {
        if (x.getOnCommitExpr() != null) {
            println();
            print0(ucase ? "ON COMMIT " : "on commit ");
            x.getOnCommitExpr().accept(this);
            print0(ucase ? " ROWS" : " rows");
        }
    }

    public void printDistributeBy(GaussDbCreateTableStatement x) {
        if (x.getDistributeBy() != null) {
            x.getDistributeBy().accept(this);
        }
    }

    public void printToGroup(GaussDbCreateTableStatement x) {
        if (x.getToGroup() != null) {
            println();
            print0(ucase ? "TO GROUP " : "to group ");
            x.getToGroup().accept(this);
        }
    }

    public void printToNode(GaussDbCreateTableStatement x) {
        if (x.getToNode() != null) {
            println();
            print0(ucase ? "TO NODE " : "to node ");
            x.getToNode().accept(this);
        }
    }

    protected void printPartitionBy(GaussDbCreateTableStatement x) {
        SQLPartitionBy partitionBy = x.getPartitioning();
        if (partitionBy != null) {
            println();
            print0(ucase ? "PARTITION BY " : "partition by ");
            partitionBy.accept(this);
        }
    }

    @Override
    public boolean visit(SQLPartitionByRange x) {
        print0(ucase ? "RANGE" : "range");
        printColumns(x.getColumns());
        if (!x.getPartitions().isEmpty()) {
            printPartitionsValue(x.getPartitions());
        }
        return false;
    }

    @Override
    public boolean visit(SQLPartitionByList x) {
        print0(ucase ? "LIST" : "list");
        printColumns(x.getColumns());
        if (!x.getPartitions().isEmpty()) {
            printPartitionsValue(x.getPartitions());
        }
        return false;
    }

    protected void printTableElements(GaussDbCreateTableStatement x) {
        int size = x.getTableElementList().size();
        if (size == 0) {
            return;
        }

        print0(" (");

        this.indentCount++;
        println();
        for (int i = 0; i < size; ++i) {
            printTableElement(x.getTableElementList(), i);
        }
        this.indentCount--;
        if (!x.getClusteredBy().isEmpty()) {
            print(',');
            println();
            print(" ");
            List<SQLExpr> clusterBy = x.getClusteredBy().stream().map(k -> k.getExpr()).collect(Collectors.toList());
            print0(ucase ? "  PARTIAL CLUSTER KEY " : "  partial cluster key");
            printColumns(clusterBy);

        }
        println();
        print(')');
    }

    protected void printTableElement(List<SQLTableElement> tableElementList, int i) {
        int size = tableElementList.size();
        SQLTableElement element = tableElementList.get(i);
        element.accept(this);

        if (i != size - 1) {
            print(',');
        }
        if (this.isPrettyFormat() && element.hasAfterComment()) {
            print(' ');
            printlnComment(element.getAfterCommentsDirect());
        }

        if (i != size - 1) {
            println();
        }
    }

    protected void printTableOptions(SQLCreateTableStatement x) {
        List<SQLAssignItem> tableOptions = x.getTableOptions();
        if (tableOptions.isEmpty()) {
            return;
        }

        println();
        print0(ucase ? "WITH (" : "with (");
        this.indentCount++;
        println();
        for (int i = 0; i < tableOptions.size(); ++i) {
            SQLAssignItem item = tableOptions.get(i);
            if (i != 0) {
                print(',');
                println();
            }
            item.getTarget().accept(this);
            print0(" = ");
            item.getValue().accept(this);
        }
        this.indentCount--;
        println();
        print(')');
    }

    protected void printCommentExpr(SQLExpr comment) {
        if (comment != null) {
            println();
            print0(ucase ? "COMMENT " : "comment ");
            comment.accept(this);
        }
    }

    @Override
    public boolean visit(SQLPartitionValue value) {
        if (value instanceof GaussDbPartitionValue) {
            GaussDbPartitionValue x = (GaussDbPartitionValue) value;
            if (x.getOperator() == SQLPartitionValue.Operator.LessThan) {
                print0(ucase ? "VALUES LESS THAN" : "values less than");
                print0(" (");
                if (x.getItems().size() == 1) {
                    // for single specific value
                    printExpr(x.getItems().get(0), parameterized);
                } else {
                    print("(");
                    printAndAccept(x.getItems(), ", ", false);
                    print(')');
                }
                print(")");
            } else if (x.getOperator() == SQLPartitionValue.Operator.StartEndEvery) {
                if (x.getStart() != null) {
                    print0(ucase ? " START (" : " start (");
                    x.getStart().accept(this);
                    print(')');
                }
                if (x.getEnd() != null) {
                    print0(ucase ? " END (" : " end (");
                    x.getEnd().accept(this);
                    print(')');
                }
                if (x.getEvery() != null) {
                    print0(ucase ? " EVERY (" : " every (");
                    x.getEvery().accept(this);
                    print(')');
                }
            } else if (x.getOperator() == SQLPartitionValue.Operator.List) {
                print0(ucase ? "VALUES " : "values ");
                print0(" (");
                if (x.getItems().size() == 1) {
                    // for single specific value
                    printExpr(x.getItems().get(0), parameterized);
                } else {
                    print("(");
                    printAndAccept(x.getItems(), ", ", false);
                    print(')');
                }
                print0(")");
            }
            if (x.getSpaceName() != null) {
                print0(ucase ? " TABLESPACE " : " tablespace ");
                x.getSpaceName().accept(this);
            } else if (x.getDataNode() != null) {
                print0(ucase ? " DATANODE " : " datanode ");
                x.getDataNode().accept(this);
            }
        } else {
            super.visit(value);
        }
        return false;
    }

    @Override
    public boolean visit(SQLPartition x) {
        SQLPartitionValue values = x.getValues();
        if (values instanceof GaussDbPartitionValue && ((GaussDbPartitionValue) values).getDistribute()) {
            print0(ucase ? "SLICE " : "slice ");
        } else {
            print0(ucase ? "PARTITION " : "partition ");
        }
        x.getName().accept(this);
        print0(" ");
        values.accept(this);
        return false;
    }

    public void printColumns(List<SQLExpr> columns) {
        if (columns.size() == 1) {
            print0(" (");
            columns.get(0).accept(this);
            print(')');
        } else {
            print0(" (");
            printAndAccept(columns, ", ");
            print(')');
        }
    }

    @Override
    public boolean visit(SQLColumnDefinition x) {
        // 1.2.5 适配：基类内联输出 COMPRESSION=，先摘除抑制，再按 gaussdb 的 COMPRESS_MODE 格式输出
        SQLCharExpr compression = x.getCompression();
        if (compression != null) {
            x.setCompression(null);
        }
        super.visit(x);
        if (compression != null) {
            x.setCompression(compression);
            print0(ucase ? " COMPRESS_MODE " : " compress_mode ");
            print0(compression.getText());
        }
        if (x.getCollateExpr() != null) {
            print0(ucase ? " COLLATE " : " collate ");
            x.getCollateExpr().accept(this);
        }
        if (x.getOnUpdate() != null) {
            print0(ucase ? " ON UPDATE " : " on update ");
            x.getOnUpdate().accept(this);
        }
        return false;
    }

    protected void printCompression(SQLColumnDefinition x) {
        // gaussdb 列压缩在 visit(SQLColumnDefinition) 中以 COMPRESS_MODE 输出
    }

    public void printPartitionsValue(List<SQLPartition> partitions) {
        print(" (");
        this.indentCount++;
        for (int i = 0, size = partitions.size(); i < size; ++i) {
            if (i != 0) {
                print(',');
            }
            println();
            SQLPartition sqlPartition = partitions.get(i);
            sqlPartition.accept(this);
        }
        this.indentCount--;
        println();
        print(')');
    }

    @Override
    public boolean visit(GaussDbDistributeBy x) {
        println();
        print0(ucase ? "DISTRIBUTE BY " : "distribute by ");
        x.getType().accept(this);
        if (!x.getColumns().isEmpty()) {
            printColumns(x.getColumns());
        }
        if (!x.getDistributions().isEmpty()) {
            printPartitionsValue(x.getDistributions());
        }
        return false;
    }

    @Override
    public boolean visit(SQLInsertStatement x) {
        if (x instanceof GaussDbInsertStatement) {
            return visit((GaussDbInsertStatement) x);
        }
        return super.visit(x);
    }

    @Override
    public boolean visit(PGInsertStatement x) {
        if (x instanceof GaussDbInsertStatement) {
            return visit((GaussDbInsertStatement) x);
        }
        return super.visit(x);
    }

    @Override
    public boolean visit(GaussDbInsertStatement x) {
        SQLWithSubqueryClause with = x.getWith();
        if (with != null) {
            visit(with);
            println();
        }
        print0(ucase ? "INSERT " : "insert ");
        if (x.isOverwrite()) {
            print0(ucase ? "OVERWRITE " : "overwrite ");
        } else if (x.isIgnore()) {
            print0(ucase ? "IGNORE " : "ignore ");
        }
        print0(ucase ? "INTO " : "into ");

        x.getTableSource().accept(this);

        printInsertColumns(x.getColumns());

        if (x.isDefaultValues()) {
            println();
            print0(ucase ? "DEFAULT VALUES" : "default values");
        }

        printValuesOrQuery(x);

        List<SQLExpr> duplicateKeyUpdate = x.getDuplicateKeyUpdate();
        if (!duplicateKeyUpdate.isEmpty()) {
            println();
            print0(ucase ? "ON DUPLICATE KEY UPDATE " : "on duplicate key update ");
            for (int i = 0, size = duplicateKeyUpdate.size(); i < size; ++i) {
                if (i != 0) {
                    if (i % 5 == 0) {
                        println();
                    }
                    print0(", ");
                }
                duplicateKeyUpdate.get(i).accept(this);
            }
        }

        printOnConflict(x);

        printReturning(x);
        return false;
    }

    protected void printReturning(PGInsertStatement x) {
        if (x.getReturning() != null) {
            println();
            print0(ucase ? "RETURNING " : "returning ");
            x.getReturning().accept(this);
        }
    }

    @Override
    public boolean visit(GaussDbVacuumStatement x) {
        print0(ucase ? "VACUUM " : "vacuum");
        if (x.isFull()) {
            print0(ucase ? "FULL " : "full ");
        }
        if (x.isFreeze()) {
            print0(ucase ? "FREEZE " : "freeze ");
        }
        if (x.isVerbose()) {
            print0(ucase ? "VERBOSE " : "verbose ");
        }
        if (x.isAnalyze()) {
            print0(ucase ? "ANALYZE " : "analyze ");
        }
        if (x.isDisablePageSkipping()) {
            print0(ucase ? "DISABLE_PAGE_SKIPPING " : "disable_page_skipping ");
        }
        if (x.isSkipLocked()) {
            print0(ucase ? "SKIP_LOCKED " : "skip_locked ");
        }
        if (x.isProcessToast()) {
            print0(ucase ? "PROCESS_TOAST " : "process_toast ");
        }
        if (x.isTruncate()) {
            print0(ucase ? "TRUNCATE " : "truncate ");
        }
        if (x.isDeltaMerge()) {
            print0(ucase ? "DELTAMERGE " : "deltamerge ");
        }
        if (x.isHdfsDirectory()) {
            print0(ucase ? "HDFSDIRECTORY " : "hdfsdirectory ");
        }
        printAndAccept(x.getTableSources(), ", ");
        return false;
    }

    /**
     * 1.2.5 适配：基类对带列清单的表源输出 "expr␣ AS alias"（双空格），
     * gaussdb 的函数表源（generate_series ... s(daytime)）改为单空格。
     */
    @Override
    public boolean visit(com.alibaba.druid.sql.ast.statement.SQLExprTableSource x) {
        java.util.List<com.alibaba.druid.sql.ast.SQLName> columns = x.getColumnsDirect();
        if (columns != null && columns.size() > 0) {
            printTableSourceExpr(x.getExpr());

            final com.alibaba.druid.sql.ast.statement.SQLTableSampling sampling = x.getSampling();
            if (sampling != null) {
                print(' ');
                sampling.accept(this);
            }

            String alias = x.getAlias();
            if (alias != null) {
                print(' ');
                print0(ucase ? "AS " : "as ");
                print0(alias);
            }

            print(" (");
            printAndAccept(columns, ", ");
            print(')');

            if (isPrettyFormat() && x.hasAfterComment()) {
                print(' ');
                printlnComment(x.getAfterCommentsDirect());
            }
            return false;
        }
        return super.visit(x);
    }

    /**
     * 1.2.5 适配：字段名为 name_lcase（新版为 nameLCase）。数值型间隔输出为
     * INTERVAL '1 MONTH'（带引号、单位大写、复数按值判断）。
     */
    @Override
    public boolean visit(com.alibaba.druid.sql.ast.expr.SQLIntervalExpr x) {
        print0(ucase ? "INTERVAL " : "interval ");
        com.alibaba.druid.sql.ast.SQLExpr value = x.getValue();

        boolean str = value instanceof com.alibaba.druid.sql.ast.expr.SQLCharExpr;
        if (!str) {
            print('\'');
        }
        value.accept(this);

        com.alibaba.druid.sql.ast.expr.SQLIntervalUnit unit = x.getUnit();
        if (unit != null) {
            print(' ');
            print0(ucase ? unit.name : unit.name_lcase);
            if (value instanceof com.alibaba.druid.sql.ast.expr.SQLIntegerExpr) {
                com.alibaba.druid.sql.ast.expr.SQLIntegerExpr integerExpr = (com.alibaba.druid.sql.ast.expr.SQLIntegerExpr) value;
                if (integerExpr.getNumber().intValue() > 1) {
                    print(ucase ? 'S' : 's');
                }
            }
        }
        if (!str) {
            print('\'');
        }
        return false;
    }

    /**
     * Oracle 兼容模式(A)：层次查询 START WITH / CONNECT BY 输出
     * （1.2.5 基类 printHierarchical 仅 Oracle 方言调用，gaussdb 挂到 PG query block）。
     */
    @Override
    public boolean visit(com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock x) {
        super.visit(x);
        printHierarchical(x);
        return false;
    }

    /**
     * 1.2.5 适配：钩子在此版本拼写为 visitAggreateRest。补打 gaussdb 的 SEPARATOR 后缀。
     */
    @Override
    protected void visitAggreateRest(SQLAggregateExpr x) {
        super.visitAggreateRest(x);
        Object value = x.getAttribute("SEPARATOR");
        if (value != null) {
            print0(ucase ? " SEPARATOR " : " separator ");
            ((SQLObject) value).accept(this);
        }
    }

    protected void printValuesOrQuery(PGInsertStatement x) {
        if (x.getValues() != null) {
            println();
            print0(ucase ? "VALUES " : "values ");
            printlnAndAccept(x.getValuesList(), ",");
        } else {
            if (x.getQuery() != null) {
                println();
                x.getQuery().accept(this);
            }
        }
    }

    protected void printOnConflict(PGInsertStatement x) {
        List<SQLExpr> onConflictTarget = x.getOnConflictTarget();
        List<SQLUpdateSetItem> onConflictUpdateSetItems = x.getOnConflictUpdateSetItems();
        boolean onConflictDoNothing = x.isOnConflictDoNothing();

        if (onConflictDoNothing
                || (onConflictTarget != null && onConflictTarget.size() > 0)
                || (onConflictUpdateSetItems != null && onConflictUpdateSetItems.size() > 0)) {
            println();
            print0(ucase ? "ON CONFLICT" : "on conflict");

            if ((onConflictTarget != null && onConflictTarget.size() > 0)) {
                print0(" (");
                printAndAccept(onConflictTarget, ", ");
                print(')');
            }

            SQLName onConflictConstraint = x.getOnConflictConstraint();
            if (onConflictConstraint != null) {
                print0(ucase ? " ON CONSTRAINT " : " on constraint ");
                printExpr(onConflictConstraint);
            }

            SQLExpr onConflictWhere = x.getOnConflictWhere();
            if (onConflictWhere != null) {
                print0(ucase ? " WHERE " : " where ");
                printExpr(onConflictWhere);
            }

            if (onConflictDoNothing) {
                print0(ucase ? " DO NOTHING" : " do nothing");
            } else if ((onConflictUpdateSetItems != null && onConflictUpdateSetItems.size() > 0)) {
                print0(ucase ? " DO UPDATE SET " : " do update set ");
                printAndAccept(onConflictUpdateSetItems, ", ");
                SQLExpr onConflictUpdateWhere = x.getOnConflictUpdateWhere();
                if (onConflictUpdateWhere != null) {
                    print0(ucase ? " WHERE " : " where ");
                    printExpr(onConflictUpdateWhere);
                }
            }
        }
    }
}
