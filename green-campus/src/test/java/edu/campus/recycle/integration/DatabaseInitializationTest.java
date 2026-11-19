package edu.campus.recycle.integration;

import edu.campus.recycle.config.DatabaseInitializer;
import edu.campus.recycle.exception.DataAccessException;
import edu.campus.recycle.util.DbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
class DatabaseInitializationTest {
    @TempDir Path directory;

    @Test
    void incompleteExistingDatabaseIsRejectedWithoutOverwritingData() throws Exception {
        DbUtil db = DbUtil.getInstance();
        String previousUrl = db.getJdbcUrl();
        try {
            db.init("jdbc:sqlite:" + directory.resolve("existing.db").toAbsolutePath());
            try (var connection = db.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE user (note TEXT NOT NULL)");
                statement.execute("INSERT INTO user (note) VALUES ('existing data')");
            }

            assertThrows(DataAccessException.class, () -> DatabaseInitializer.initialize(true));
            try (var connection = db.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT note FROM user")) {
                assertTrue(result.next());
                assertEquals("existing data", result.getString(1));
            }
        } finally {
            db.init(previousUrl);
        }
    }
}
