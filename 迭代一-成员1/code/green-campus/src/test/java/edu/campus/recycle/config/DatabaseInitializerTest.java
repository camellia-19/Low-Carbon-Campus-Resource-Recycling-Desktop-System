package edu.campus.recycle.config;

import edu.campus.recycle.support.DatabaseTestSupport;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseInitializerTest extends DatabaseTestSupport {
    @Test void repeatedStartupPreservesUserDataAndDoesNotReseed() throws Exception {
        int id = user("persistent_user").getId();
        dao.getSystemConfigDao().upsert("credit.init", "88", "用户自定义配置");
        DatabaseInitializer.initialize(true);
        DatabaseInitializer.initialize(false);
        assertEquals(id, dao.getUserDao().findByUsername("persistent_user").orElseThrow().getId());
        assertEquals(6, dao.getUserDao().findAll().size());
        assertEquals(88, dao.getSystemConfigDao().getInt("credit.init", 0));
        assertEquals(1, scalar("PRAGMA user_version"));
    }

    @Test void fileReopenedByNewConnectionKeepsData() throws Exception {
        user("reopen_user");
        String url = db.getJdbcUrl();
        db.init(url);
        DatabaseInitializer.initialize(true);
        assertTrue(dao.getUserDao().existsByUsername("reopen_user"));
        assertTrue(Files.size(temp.resolve("test.db")) > 0);
    }

    @Test void emptyFileIsInitializedWithoutSeed() throws Exception {
        Files.createFile(temp.resolve("empty.db"));
        db.init("jdbc:sqlite:" + temp.resolve("empty.db"));
        DatabaseInitializer.initialize(false);
        assertEquals(0, dao.getUserDao().findAll().size());
        assertEquals(11, scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"));
        assertEquals(1, scalar("PRAGMA user_version"));
    }

    @Test void legacyDatabaseIsRegisteredWithoutDroppingData() throws Exception {
        user("legacy_user"); sql("PRAGMA user_version=0");
        DatabaseInitializer.initialize(true);
        assertTrue(dao.getUserDao().existsByUsername("legacy_user"));
        assertEquals(1, scalar("PRAGMA user_version"));
    }

    @Test void incompleteDatabaseIsRejectedAndPreserved() throws Exception {
        db.init("jdbc:sqlite:" + temp.resolve("incomplete.db"));
        sql("CREATE TABLE notes (body TEXT)"); sql("INSERT INTO notes VALUES ('保留')");
        assertThrows(IllegalStateException.class, () -> DatabaseInitializer.initialize(true));
        assertEquals(1, scalar("SELECT COUNT(*) FROM notes"));
        assertEquals(0, scalar("PRAGMA user_version"));
    }

    @Test void futureVersionIsRejectedWithoutChangingData() throws Exception {
        user("future_user"); sql("PRAGMA user_version=99");
        assertThrows(IllegalStateException.class, () -> DatabaseInitializer.initialize(true));
        assertEquals(99, scalar("PRAGMA user_version"));
        assertTrue(dao.getUserDao().existsByUsername("future_user"));
    }

    @Test void missingColumnIsRejectedWithoutRegisteringVersion() throws Exception {
        sql("ALTER TABLE item RENAME COLUMN tags TO wrong_tags"); sql("PRAGMA user_version=0");
        assertThrows(RuntimeException.class, () -> DatabaseInitializer.initialize(false));
        assertEquals(0, scalar("PRAGMA user_version"));
        assertEquals(12, scalar("SELECT COUNT(*) FROM item"));
    }

    @Test void explicitResetRecreatesOnlySelectedTemporaryDatabase() {
        user("reset_user");
        DatabaseInitializer.reset(false);
        assertTrue(dao.getUserDao().findAll().isEmpty());
        DatabaseInitializer.reset(true);
        assertEquals(5, dao.getUserDao().findAll().size());
    }

    @Test void sharedMemoryDatabaseWorksWhileKeeperConnectionIsOpen() throws Exception {
        db.init("jdbc:sqlite:file:test" + System.nanoTime() + "?mode=memory&cache=shared");
        try (var keeper = db.getConnection()) {
            DatabaseInitializer.initialize(false);
            user("memory_user");
            assertTrue(dao.getUserDao().existsByUsername("memory_user"));
        }
    }

    @Test void parserPreservesQuotedSemicolonsAndEscapes() {
        assertEquals(List.of("INSERT INTO x VALUES ('a;--b''c')", "SELECT \"a;b\""),
                DatabaseInitializer.splitStatements("\uFEFF-- comment\nINSERT INTO x VALUES ('a;--b''c'); /* ; */ SELECT \"a;b\";"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseInitializer.splitStatements("SELECT 'unclosed"));
        assertThrows(IllegalArgumentException.class, () -> DatabaseInitializer.splitStatements("/* unclosed"));
    }
}
