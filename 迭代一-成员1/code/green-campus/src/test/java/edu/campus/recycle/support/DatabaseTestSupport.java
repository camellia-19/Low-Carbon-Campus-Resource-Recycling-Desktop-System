package edu.campus.recycle.support;

import edu.campus.recycle.config.DatabaseInitializer;
import edu.campus.recycle.entity.*;
import edu.campus.recycle.util.DaoFactory;
import edu.campus.recycle.util.DbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;

/** 每个测试使用独立临时文件，绝不读写开发者的 data/greencampus.db。 */
public abstract class DatabaseTestSupport {
    @TempDir protected Path temp;
    protected final DbUtil db = DbUtil.getInstance();
    protected final DaoFactory dao = DaoFactory.getInstance();
    private String originalUrl;

    @BeforeEach void createDatabase() {
        originalUrl = db.getJdbcUrl();
        db.init("jdbc:sqlite:" + temp.resolve("test.db"));
        DatabaseInitializer.initialize(true);
    }

    @AfterEach void restoreDatabase() {
        db.init(originalUrl);
    }

    protected void sql(String sql) throws SQLException {
        try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement()) { stmt.execute(sql); }
    }

    protected int scalar(String sql) throws SQLException {
        try (Connection conn = db.getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    protected User user(String username) {
        User user = new User();
        user.setUsername(username); user.setPasswordHash("test-hash"); user.setSalt("test-salt");
        user.setRealName("测试同学");
        dao.getUserDao().insert(user);
        return dao.getUserDao().findById(user.getId()).orElseThrow();
    }

    protected Item item(String title) {
        Item item = new Item();
        item.setOwnerId(3); item.setCategoryId(6); item.setTitle(title); item.setDescription("课程测试物品");
        item.setTradeType("SELL"); item.setPrice(new BigDecimal("12.34"));
        dao.getItemDao().insert(item);
        return dao.getItemDao().findById(item.getId()).orElseThrow();
    }

    protected TradeFlow flow(int itemId) {
        TradeFlow flow = new TradeFlow();
        flow.setItemId(itemId); flow.setSellerId(3); flow.setBuyerId(4);
        flow.setFlowType("SELL"); flow.setAmount(new BigDecimal("12.34"));
        dao.getTradeFlowDao().insert(flow);
        return dao.getTradeFlowDao().findById(flow.getId()).orElseThrow();
    }

    protected ItemImage image(int itemId, String path) {
        ItemImage image = new ItemImage(); image.setItemId(itemId); image.setFilePath(path);
        return image;
    }
}
