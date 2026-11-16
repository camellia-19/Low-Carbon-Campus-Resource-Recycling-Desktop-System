package edu.campus.recycle.util;

import edu.campus.recycle.entity.Item;
import edu.campus.recycle.exception.DataAccessException;
import edu.campus.recycle.exception.TransactionException;
import edu.campus.recycle.support.DatabaseTestSupport;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DbUtilTest extends DatabaseTestSupport {
    @Test void eachConnectionEnablesForeignKeysAndBusyTimeout() throws Exception {
        for (int i = 0; i < 2; i++) {
            assertEquals(1, scalar("PRAGMA foreign_keys"));
            assertEquals(5000, scalar("PRAGMA busy_timeout"));
        }
    }

    @Test void commitPersistsMultipleDaoChanges() {
        db.executeInTransaction(conn -> {
            user("committed_user"); dao.getSystemConfigDao().upsert("test.commit", "1", "提交测试");
            assertSame(conn, db.currentConnection());
            return null;
        });
        assertTrue(dao.getUserDao().existsByUsername("committed_user"));
        assertEquals("1", dao.getSystemConfigDao().getValue("test.commit"));
        assertFalse(db.isInTransaction());
    }

    @Test void runtimeExceptionRollsBackEveryDaoAndReleasesContext() {
        assertThrows(IllegalArgumentException.class, () -> db.executeInTransaction(conn -> {
            user("rolledback_user"); item("回滚物品"); throw new IllegalArgumentException("故障注入");
        }));
        assertFalse(dao.getUserDao().existsByUsername("rolledback_user"));
        assertEquals(12, dao.getItemDao().findAll().size());
        assertFalse(db.isInTransaction());
        user("next_transaction");
        assertTrue(dao.getUserDao().existsByUsername("next_transaction"));
    }

    @Test void checkedExceptionIsWrappedAndRolledBack() {
        TransactionException error = assertThrows(TransactionException.class, () -> db.executeInTransaction(conn -> {
            user("checked_error"); throw new SQLException("故障注入");
        }));
        assertInstanceOf(SQLException.class, error.getCause());
        assertFalse(dao.getUserDao().existsByUsername("checked_error"));
    }

    @Test void errorAlsoRollsBack() {
        assertThrows(AssertionError.class, () -> db.executeInTransaction(conn -> {
            user("error_user"); throw new AssertionError("故障注入");
        }));
        assertFalse(dao.getUserDao().existsByUsername("error_user"));
        assertFalse(db.isInTransaction());
    }

    @Test void nestedSuccessUsesSameConnectionAndCannotCommitIndependently() {
        assertThrows(IllegalStateException.class, () -> db.executeInTransaction(outer -> {
            db.executeInTransaction(inner -> { assertSame(outer, inner); user("nested_user"); return null; });
            throw new IllegalStateException("外层失败");
        }));
        assertFalse(dao.getUserDao().existsByUsername("nested_user"));
    }

    @Test void caughtNestedFailureStillForcesOuterRollback() {
        assertThrows(TransactionException.class, () -> db.executeInTransaction(outer -> {
            user("before_nested");
            try { db.executeInTransaction(inner -> { throw new IllegalStateException("内层失败"); }); }
            catch (IllegalStateException expected) { /* 模拟业务层捕获异常后继续 */ }
            user("after_nested"); return null;
        }));
        assertFalse(dao.getUserDao().existsByUsername("before_nested"));
        assertFalse(dao.getUserDao().existsByUsername("after_nested"));
    }

    @Test void caughtDaoSqlFailureStillForcesOuterRollback() {
        assertThrows(TransactionException.class, () -> db.executeInTransaction(conn -> {
            user("before_sql_error");
            try { user("admin"); } catch (DataAccessException expected) { /* 唯一约束失败被捕获 */ }
            return null;
        }));
        assertFalse(dao.getUserDao().existsByUsername("before_sql_error"));
    }

    @Test void imageBatchFailureDoesNotLeaveEarlierRows() {
        Item item = item("图片批量测试");
        assertThrows(DataAccessException.class, () -> dao.getItemImageDao().batchInsert(
                List.of(image(item.getId(), "data/images/ok.png"), image(999999, "data/images/bad.png"))));
        assertTrue(dao.getItemImageDao().findByItemId(item.getId()).isEmpty());
    }

    @Test void transactionsAreIsolatedBetweenThreads() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            db.executeInTransaction(conn -> {
                Connection other = executor.submit(() -> {
                    assertFalse(db.isInTransaction());
                    try (Connection independent = db.currentConnection()) { return independent; }
                }).get(5, TimeUnit.SECONDS);
                assertNotSame(conn, other);
                assertFalse(conn.isClosed());
                return null;
            });
        } finally { executor.shutdownNow(); }
    }

    @Test void switchingDatabaseInsideTransactionIsRejected() {
        String url = db.getJdbcUrl();
        assertThrows(IllegalStateException.class, () -> db.executeInTransaction(conn -> {
            db.init("jdbc:sqlite:another.db"); return null;
        }));
        assertEquals(url, db.getJdbcUrl());
    }
}
