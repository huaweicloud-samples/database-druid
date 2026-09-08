package com.alibaba.druid.sql.dialect.gaussdb.ast.stmt;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatementImpl;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.dialect.gaussdb.visitor.GaussDbASTVisitor;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.2.5 适配：基于 fork 的 PGVacuumStatement 移植（1.2.5 的 PG 方言无 VACUUM），
 * 分发改为 GaussDbASTVisitor。支持 gaussdb 的 DELTAMERGE / HDFSDIRECTORY 扩展。
 *
 * @see <a href="https://www.postgresql.org/docs/current/sql-vacuum.html">VACUUM — garbage-collect and optionally analyze a database</a>
 */
public class GaussDbVacuumStatement extends SQLStatementImpl {
    private boolean full;
    private boolean freeze;
    private boolean verbose;
    private boolean skipLocked;
    private boolean analyze;
    private boolean disablePageSkipping;
    private boolean processToast;
    private boolean truncate;
    // gaussdb specific
    private boolean deltaMerge;
    private boolean hdfsDirectory;
    private List<SQLExprTableSource> tableSources = new ArrayList<SQLExprTableSource>();

    public GaussDbVacuumStatement(DbType dbType) {
        super.dbType = dbType;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public boolean isSkipLocked() {
        return skipLocked;
    }

    public void setSkipLocked(boolean skipLocked) {
        this.skipLocked = skipLocked;
    }

    public List<SQLExprTableSource> getTableSources() {
        return tableSources;
    }

    public void setTableSources(List<SQLExprTableSource> tableSources) {
        this.tableSources = tableSources;
    }

    public boolean isFull() {
        return full;
    }

    public void setFull(boolean full) {
        this.full = full;
    }

    public boolean isFreeze() {
        return freeze;
    }

    public void setFreeze(boolean freeze) {
        this.freeze = freeze;
    }

    public boolean isAnalyze() {
        return analyze;
    }

    public void setAnalyze(boolean analyze) {
        this.analyze = analyze;
    }

    public boolean isDisablePageSkipping() {
        return disablePageSkipping;
    }

    public void setDisablePageSkipping(boolean disablePageSkipping) {
        this.disablePageSkipping = disablePageSkipping;
    }

    public boolean isProcessToast() {
        return processToast;
    }

    public void setProcessToast(boolean processToast) {
        this.processToast = processToast;
    }

    public boolean isTruncate() {
        return truncate;
    }

    public void setTruncate(boolean truncate) {
        this.truncate = truncate;
    }

    public boolean isDeltaMerge() {
        return deltaMerge;
    }

    public void setDeltaMerge(boolean deltaMerge) {
        this.deltaMerge = deltaMerge;
    }

    public boolean isHdfsDirectory() {
        return hdfsDirectory;
    }

    public void setHdfsDirectory(boolean hdfsDirectory) {
        this.hdfsDirectory = hdfsDirectory;
    }

    @Override
    protected void accept0(SQLASTVisitor visitor) {
        if (visitor instanceof GaussDbASTVisitor) {
            accept0((GaussDbASTVisitor) visitor);
        }
    }

    public void accept0(GaussDbASTVisitor visitor) {
        if (visitor.visit(this)) {
            acceptChild(visitor, tableSources);
        }
        visitor.endVisit(this);
    }
}
