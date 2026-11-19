package edu.campus.recycle.config;

import edu.campus.recycle.exception.DataAccessException;
import edu.campus.recycle.util.DbUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 数据库初始化器：仅为空库建立结构，按需在同一事务中导入演示数据。
 *
 * <p>脚本按分号拆分逐条执行；拆分时识别单引号字符串与 {@code --} 行注释，避免误切。</p>
 */
public final class DatabaseInitializer {

    private static final Logger LOG = Logger.getLogger(DatabaseInitializer.class.getName());
    private static final Set<String> REQUIRED_TABLES = Set.of("user", "category", "item", "item_image",
            "favorite", "trade_message", "trade_flow", "review", "credit_log", "operation_log", "system_config");

    private DatabaseInitializer() {
    }

    /**
     * 启动时确保数据库可用。演示种子开关仅在首次建库时生效。
     */
    public static void initialize() {
        initialize(DbConfig.getInstance().isSeedOnStart());
    }

    /**
     * 初始化数据库。
     *
     * @param withSeed 空库建表时是否导入演示种子数据
     */
    public static void initialize(boolean withSeed) {
        ensureDatabaseDirectory();
        boolean created = DbUtil.getInstance().executeInTransaction(conn -> initializeIfEmpty(conn, withSeed));
        if (created) {
            enableWal();
            LOG.info("新数据库已初始化");
        } else {
            LOG.info("已打开现有数据库");
        }
    }

    private static boolean initializeIfEmpty(Connection conn, boolean withSeed) throws SQLException {
        Set<String> tables = readTables(conn);
        if (!tables.isEmpty()) {
            requireCompleteSchema(tables);
            return false;
        }
        DbConfig config = DbConfig.getInstance();
        runScript(conn, Paths.get(config.getSchemaPath()), "建库脚本", false);
        requireCompleteSchema(readTables(conn));
        if (withSeed) {
            runScript(conn, Paths.get(config.getSeedPath()), "种子数据脚本", true);
        }
        return true;
    }

    private static Set<String> readTables(Connection conn) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (var statement = conn.createStatement();
             var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (result.next()) {
                String name = result.getString(1);
                if (!name.startsWith("sqlite_")) {
                    tables.add(name);
                }
            }
        }
        return tables;
    }

    private static void requireCompleteSchema(Set<String> tables) {
        if (!tables.containsAll(REQUIRED_TABLES)) {
            var missing = new java.util.TreeSet<>(REQUIRED_TABLES);
            missing.removeAll(tables);
            throw DataAccessException.of("数据库结构不完整，缺少表：" + String.join("、", missing));
        }
    }

    private static void enableWal() {
        try (var conn = DbUtil.getInstance().getConnection(); var statement = conn.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "无法启用 WAL，继续使用 SQLite 当前日志模式", e);
        }
    }

    /**
     * 确保 SQLite 数据库文件所在目录存在（首次运行时 data 目录通常不存在）。
     */
    private static void ensureDatabaseDirectory() {
        String jdbcUrl = DbUtil.getInstance().getJdbcUrl();
        final String prefix = "jdbc:sqlite:";
        if (jdbcUrl == null || !jdbcUrl.startsWith(prefix)) {
            return;
        }
        String filePart = jdbcUrl.substring(prefix.length());
        if (filePart.isEmpty() || filePart.startsWith(":memory:") || filePart.startsWith("file:")) {
            return;
        }
        Path parent = Paths.get(filePart).toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new DataAccessException("无法创建数据库目录：" + parent, e);
        }
    }

    /** 脚本的事务和连接设置由启动器接管。 */
    private static void runScript(Connection conn, Path path, String label, boolean seed) throws SQLException {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw DataAccessException.of("读取" + label + "失败：" + path, e);
        }
        try (Statement statement = conn.createStatement()) {
            for (String sql : splitStatements(content)) {
                String command = sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (command.startsWith("PRAGMA ") || (seed && (command.equals("BEGIN TRANSACTION") || command.equals("COMMIT")))) {
                    continue;
                }
                boolean allowed = seed ? command.startsWith("INSERT INTO ")
                        : command.startsWith("CREATE TABLE ") || command.startsWith("CREATE INDEX ")
                        || command.startsWith("CREATE UNIQUE INDEX ");
                if (!allowed) {
                    throw DataAccessException.of(label + "包含不适用于首次初始化的语句");
                }
                statement.execute(sql);
            }
        }
    }

    /**
     * 按分号拆分 SQL 语句：忽略行注释与字符串内的分号。
     *
     * @param content 脚本全文
     * @return 语句列表
     */
    static List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inComment = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inComment) {
                if (ch == '\n') {
                    inComment = false;
                    current.append('\n');
                }
                continue;
            }
            if (!inSingleQuote && ch == '-' && i + 1 < content.length() && content.charAt(i + 1) == '-') {
                inComment = true;
                i++;
                continue;
            }
            if (ch == '\'') {
                if (inSingleQuote && i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                    current.append("''");
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                current.append(ch);
                continue;
            }
            if (ch == ';' && !inSingleQuote) {
                addStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder buffer) {
        String sql = buffer.toString().trim();
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
    }
}
