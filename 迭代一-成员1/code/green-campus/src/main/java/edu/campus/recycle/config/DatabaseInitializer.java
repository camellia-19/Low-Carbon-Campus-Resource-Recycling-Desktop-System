package edu.campus.recycle.config;

import edu.campus.recycle.exception.DataAccessException;
import edu.campus.recycle.util.DbUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.regex.*;

/** 首次建库及旧库版本登记。正常启动不清空数据，不重复导入演示账号。 */
public final class DatabaseInitializer {
    public static final int SCHEMA_VERSION = 1;
    private static final Set<String> TABLES = Set.of("user", "category", "item", "item_image",
            "favorite", "trade_message", "trade_flow", "review", "credit_log", "operation_log", "system_config");
    private DatabaseInitializer() { }

    public static void initialize() {
        initialize(DbConfig.getInstance().isSeedOnStart());
    }

    /** 使用 DbUtil 当前 URL，测试可显式选择临时库。已有完整旧库仅登记版本。 */
    public static synchronized void initialize(boolean withSeed) {
        ensureDatabaseDirectory();
        List<String> schema = schema();
        List<String> seed = seed(withSeed);
        DbUtil.getInstance().executeInTransaction(conn -> {
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                if (rs.next() && rs.getInt(1) > SCHEMA_VERSION)
                    throw new IllegalStateException("数据库版本高于程序支持版本，请使用新版程序");
            }
            Set<String> existing = new HashSet<>();
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                while (rs.next()) existing.add(rs.getString(1));
            }
            if (existing.isEmpty()) {
                execute(conn, schema, true);
                execute(conn, seed, false);
            } else if (!existing.containsAll(TABLES)) {
                throw new IllegalStateException("数据库结构不完整，已保留原数据；请先备份后检查数据库");
            }
            validate(conn, schema);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
            return null;
        });
    }

    /** 显式重置当前数据库，会清空数据；只供 --reset-db 和独立演示库调用。 */
    public static synchronized void reset(boolean withSeed) {
        ensureDatabaseDirectory();
        List<String> schema = schema();
        List<String> seed = seed(withSeed);
        if (DbUtil.getInstance().isInTransaction()) throw new IllegalStateException("不能在业务事务中重置数据库");
        try (Connection conn = DbUtil.getInstance().getConnection(); Statement stmt = conn.createStatement()) {
            // 外键开关只能在事务开始前切换。重置的 DDL 和种子导入在同一事务内。
            stmt.execute("PRAGMA foreign_keys = OFF");
            conn.setAutoCommit(false);
            try {
                execute(conn, schema, false);
                execute(conn, seed, false);
                validate(conn, schema);
                stmt.execute("PRAGMA user_version = " + SCHEMA_VERSION);
                conn.commit();
            } catch (SQLException | RuntimeException | Error e) {
                try { conn.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            throw DataAccessException.of("重置数据库失败，已回滚", e);
        }
    }

    private static List<String> schema() {
        return splitStatements(readScript(DbConfig.getInstance().getSchemaPath(), "schema.sql"));
    }

    private static List<String> seed(boolean enabled) {
        return enabled ? splitStatements(readScript(DbConfig.getInstance().getSeedPath(), "seed-data.sql")) : List.of();
    }

    private static void validate(Connection conn, List<String> schema) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) throw new SQLException("数据库存在外键违规：" + rs.getString(1));
        }
        Pattern tablePattern = Pattern.compile("(?is)^CREATE\\s+TABLE\\s+\"?(\\w+)\"?\\s*\\(");
        Pattern columnPattern = Pattern.compile("(?im)^\\s*(\\w+)\\s+(?:INTEGER|TEXT|REAL)\\b");
        for (String sql : schema) {
            Matcher table = tablePattern.matcher(sql);
            if (!table.find()) continue;
            Matcher column = columnPattern.matcher(sql);
            List<String> columns = new ArrayList<>();
            while (column.find()) columns.add("[" + column.group(1) + "]");
            // 表名/列名只取受信任的建库脚本；LIMIT 0 校验全部必需列而不读取用户数据。
            try (Statement stmt = conn.createStatement()) {
                stmt.executeQuery("SELECT " + String.join(",", columns) + " FROM \"" + table.group(1) + "\" LIMIT 0").close();
            }
        }
    }

    private static void execute(Connection conn, List<String> statements, boolean fresh) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String sql : statements) {
                String upper = sql.stripLeading().toUpperCase(Locale.ROOT);
                if (upper.startsWith("PRAGMA ") || upper.startsWith("BEGIN")
                        || upper.equals("COMMIT") || upper.equals("ROLLBACK")
                        || (fresh && upper.startsWith("DROP "))) continue;
                stmt.execute(sql);
            }
        }
    }

    private static String readScript(String configuredPath, String name) {
        Path path = Path.of(configuredPath);
        try {
            if (Files.isRegularFile(path)) return Files.readString(path, StandardCharsets.UTF_8);
            try (InputStream in = DatabaseInitializer.class.getResourceAsStream("/db/" + name)) {
                if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("SQL 脚本不存在：" + path.toAbsolutePath() + "，Jar 内也未包含 /db/" + name);
        } catch (IOException e) {
            throw DataAccessException.of("读取 SQL 脚本失败：" + name, e);
        }
    }

    private static void ensureDatabaseDirectory() {
        String file = DbUtil.getInstance().getJdbcUrl().substring("jdbc:sqlite:".length());
        if (file.isEmpty() || file.equals(":memory:") || file.contains("mode=memory")) return;
        try {
            Path database = file.startsWith("file:")
                    ? Path.of(java.net.URI.create(file.split("\\?", 2)[0])) : Path.of(file);
            Path parent = database.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            throw DataAccessException.of("无法创建数据库目录", e);
        }
    }

    /** 支持转义引号、行注释和块注释；当前脚本不含触发器。 */
    static List<String> splitStatements(String content) {
        List<String> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        char quote = 0;
        boolean line = false, block = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            char next = i + 1 < content.length() ? content.charAt(i + 1) : 0;
            if (line) { if (ch == '\n') { line = false; buffer.append('\n'); } continue; }
            if (block) { if (ch == '*' && next == '/') { block = false; i++; buffer.append(' '); } continue; }
            if (quote != 0) {
                buffer.append(ch);
                if (ch == quote) { if (next == quote) { buffer.append(next); i++; } else quote = 0; }
                continue;
            }
            if (ch == '-' && next == '-') { line = true; i++; buffer.append(' '); continue; }
            if (ch == '/' && next == '*') { block = true; i++; buffer.append(' '); continue; }
            if (ch == '\'' || ch == '"') { quote = ch; buffer.append(ch); continue; }
            if (ch == ';') {
                if (!buffer.toString().isBlank()) result.add(buffer.toString().trim());
                buffer.setLength(0);
            } else if (ch != '\uFEFF') buffer.append(ch);
        }
        if (quote != 0 || block) throw new IllegalArgumentException("SQL 脚本存在未闭合的引号或注释");
        if (!buffer.toString().isBlank()) result.add(buffer.toString().trim());
        return result;
    }
}
