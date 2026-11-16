package edu.campus.recycle.dao;

import edu.campus.recycle.dto.ItemQuery;
import edu.campus.recycle.entity.*;
import edu.campus.recycle.exception.DataAccessException;
import edu.campus.recycle.support.DatabaseTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DaoPersistenceTest extends DatabaseTestSupport {
    @Test void userCrudPreservesNullableFieldsAndLoginState() {
        User user = user("dao_student");
        assertNotNull(user.getId()); assertNull(user.getStudentNo()); assertNull(user.getLockedUntil());
        assertEquals("STUDENT", user.getRole()); assertEquals(100, user.getCreditScore());
        user.setRealName("修改姓名"); user.setContact("student@campus.edu.cn");
        assertEquals(1, dao.getUserDao().update(user));
        assertEquals("修改姓名", dao.getUserDao().findById(user.getId()).orElseThrow().getRealName());
        LocalDateTime lock = LocalDateTime.of(2026, 11, 16, 12, 0);
        dao.getUserDao().updateFailCount(user.getId(), 5, lock);
        assertEquals(lock, dao.getUserDao().findById(user.getId()).orElseThrow().getLockedUntil());
        dao.getUserDao().updateLoginSuccess(user.getId(), lock.plusMinutes(16));
        User loggedIn = dao.getUserDao().findById(user.getId()).orElseThrow();
        assertNull(loggedIn.getLockedUntil()); assertEquals(0, loggedIn.getFailCount());
        assertEquals(1, dao.getUserDao().deleteById(user.getId()));
        assertTrue(dao.getUserDao().findById(user.getId()).isEmpty());
    }

    @Test void userQueriesAreParameterizedAndPaginationDoesNotOverflow() {
        assertTrue(dao.getUserDao().findByUsername("' OR 1=1 --").isEmpty());
        assertTrue(dao.getUserDao().findByCondition("' OR 1=1 --", null, 1, 10).isEmpty());
        assertEquals(3, dao.getUserDao().countByCondition(null, "STUDENT"));
        assertEquals(1, dao.getUserDao().findByCondition(null, "STUDENT", 2, 1).size());
        assertTrue(dao.getUserDao().findByCondition(null, null, Integer.MAX_VALUE, 100).isEmpty());
        assertEquals(5, dao.getUserDao().findByCondition(null, null, 0, 0).size());
    }

    @Test void categoryCrudKeepsRootNullAndRejectsDuplicateRoot() {
        Category root = new Category(); root.setName("实验分类");
        dao.getCategoryDao().insert(root);
        Category stored = dao.getCategoryDao().findById(root.getId()).orElseThrow();
        assertNull(stored.getParentId()); assertTrue(stored.getEnabled());
        assertTrue(dao.getCategoryDao().existsByName(null, "实验分类"));
        Category duplicate = new Category(); duplicate.setName("实验分类");
        assertThrows(DataAccessException.class, () -> dao.getCategoryDao().insert(duplicate));
        stored.setName("实验分类修订"); dao.getCategoryDao().update(stored);
        dao.getCategoryDao().updateEnabled(stored.getId(), false);
        assertFalse(dao.getCategoryDao().findById(stored.getId()).orElseThrow().getEnabled());
        assertEquals(1, dao.getCategoryDao().deleteById(stored.getId()));
    }

    @Test void categoryChildrenAndForeignKeyRestrictionsWork() {
        assertEquals(4, dao.getCategoryDao().findByParentId(null).size());
        assertEquals(2, dao.getCategoryDao().findByParentId(1).size());
        assertThrows(DataAccessException.class, () -> dao.getCategoryDao().deleteById(1));
        assertTrue(dao.getCategoryDao().findById(1).isPresent());
    }

    @Test void itemCrudPreservesMoneyAndDefaultPendingState() {
        Item item = item("DAO 持久化物品");
        assertEquals("PENDING", item.getStatus()); assertNull(item.getPublishedAt());
        assertEquals(0, new BigDecimal("12.34").compareTo(item.getPrice()));
        item.setTitle("修改后的物品"); item.setTags("教材,测试"); dao.getItemDao().update(item);
        assertEquals("教材,测试", dao.getItemDao().findById(item.getId()).orElseThrow().getTags());
        dao.getItemDao().increaseViewCount(item.getId());
        dao.getItemDao().increaseFavoriteCount(item.getId(), -5);
        assertEquals(1, dao.getItemDao().findById(item.getId()).orElseThrow().getViewCount());
        assertEquals(0, dao.getItemDao().findById(item.getId()).orElseThrow().getFavoriteCount());
        dao.getItemDao().deleteById(item.getId());
        assertTrue(dao.getItemDao().findById(item.getId()).isEmpty());
    }

    @Test void itemSearchCombinesCategoryPriceKeywordAndSorting() {
        ItemQuery query = new ItemQuery(); query.setCategoryId(1); query.setStatus("ON_SALE");
        query.setKeyword("英语"); query.setMinPrice(new BigDecimal("10")); query.setMaxPrice(new BigDecimal("20"));
        assertEquals(1, dao.getItemDao().countSearch(query));
        assertEquals(2, dao.getItemDao().search(query).get(0).getId());
        query.setKeyword(null); query.setCategoryId(null); query.setMinPrice(null); query.setMaxPrice(null);
        query.setSortBy("PRICE_ASC"); query.setPageSize(2);
        List<Item> items = dao.getItemDao().search(query);
        assertEquals(2, items.size()); assertTrue(items.get(0).getPrice().compareTo(items.get(1).getPrice()) <= 0);
        query.setPageNo(Integer.MAX_VALUE);
        assertTrue(dao.getItemDao().search(query).isEmpty());
    }

    @Test void itemSearchRejectsSqlInjectionAsDataAndUsesSortWhitelist() {
        ItemQuery query = new ItemQuery(); query.setKeyword("' OR 1=1 --");
        assertTrue(dao.getItemDao().search(query).isEmpty());
        query.setKeyword(null); query.setSortBy("price; DROP TABLE item; --");
        assertFalse(dao.getItemDao().search(query).isEmpty());
        assertEquals(12, dao.getItemDao().findAll().size());
    }

    @Test void imageCrudBatchOrderingAndCascadeDeleteWork() {
        Item item = item("图片顺序测试");
        ItemImage one = image(item.getId(), "data/images/one.png"); one.setSortOrder(2);
        dao.getItemImageDao().insert(one);
        assertNotNull(one.getId());
        one.setFilePath("data/images/updated.png"); dao.getItemImageDao().update(one);
        assertEquals("data/images/updated.png", dao.getItemImageDao().findById(one.getId()).orElseThrow().getFilePath());
        assertEquals(2, dao.getItemImageDao().batchInsert(List.of(
                image(item.getId(), "data/images/first.png"), image(item.getId(), "data/images/second.jpg"))));
        assertEquals("data/images/first.png", dao.getItemImageDao().findByItemId(item.getId()).get(0).getFilePath());
        assertEquals(0, dao.getItemImageDao().batchInsert(List.of()));
        dao.getItemImageDao().deleteById(one.getId());
        dao.getItemDao().deleteById(item.getId());
        assertTrue(dao.getItemImageDao().findByItemId(item.getId()).isEmpty());
    }

    @Test void favoriteCrudUniquenessJoinAndCascadeWork() {
        Item item = item("收藏测试");
        Favorite favorite = new Favorite(); favorite.setUserId(4); favorite.setItemId(item.getId());
        dao.getFavoriteDao().insert(favorite);
        assertEquals(1, dao.getFavoriteDao().countByItemId(item.getId()));
        assertTrue(dao.getFavoriteDao().exists(4, item.getId()));
        assertTrue(dao.getFavoriteDao().findItemsByUserId(4).stream().anyMatch(i -> i.getId().equals(item.getId())));
        Favorite duplicate = new Favorite(); duplicate.setUserId(4); duplicate.setItemId(item.getId());
        assertThrows(DataAccessException.class, () -> dao.getFavoriteDao().insert(duplicate));
        favorite.setUserId(5); dao.getFavoriteDao().update(favorite);
        assertEquals(5, dao.getFavoriteDao().findById(favorite.getId()).orElseThrow().getUserId());
        assertEquals(1, dao.getFavoriteDao().delete(5, item.getId()));
        assertFalse(dao.getFavoriteDao().exists(5, item.getId()));
    }

    @Test void flowCrudOnlyAllowsOneActiveFlowPerItem() {
        Item item = item("流转测试"); TradeFlow flow = flow(item.getId());
        assertEquals("APPLYING", flow.getStatus()); assertNull(flow.getFinishedAt());
        assertThrows(DataAccessException.class, () -> flow(item.getId()));
        flow.setApplyRemark("更新留言"); dao.getTradeFlowDao().update(flow);
        assertEquals("更新留言", dao.getTradeFlowDao().findById(flow.getId()).orElseThrow().getApplyRemark());
        dao.getTradeFlowDao().updateStatus(flow.getId(), "CANCELLED", "测试取消", LocalDateTime.now());
        assertTrue(dao.getTradeFlowDao().findActiveByItemId(item.getId()).isEmpty());
        TradeFlow next = flow(item.getId());
        assertNotEquals(flow.getId(), next.getId());
        assertTrue(dao.getTradeFlowDao().findByUserId(4, null, Integer.MAX_VALUE, 100).isEmpty());
        assertEquals(1, dao.getTradeFlowDao().deleteById(next.getId()));
    }

    @Test void statisticsIncludeOnlyCompletedFlowsInPeriod() {
        var report = dao.getTradeFlowDao().countByCategoryForPeriod(
                LocalDateTime.of(2020, 1, 1, 0, 0), LocalDateTime.of(2030, 1, 1, 0, 0));
        assertEquals(2, report.stream().mapToLong(r -> r.getFlowCount()).sum());
        var empty = dao.getTradeFlowDao().countByCategoryForPeriod(
                LocalDateTime.of(2030, 1, 1, 0, 0), LocalDateTime.of(2031, 1, 1, 0, 0));
        assertEquals(0, empty.stream().mapToLong(r -> r.getFlowCount()).sum());
    }

    @Test void messageCrudConversationAndReadStateWork() {
        TradeMessage message = new TradeMessage(); message.setItemId(2); message.setFromUserId(4);
        message.setToUserId(3); message.setContent("测试沟通");
        int unreadBefore = dao.getTradeMessageDao().countUnread(3);
        dao.getTradeMessageDao().insert(message);
        assertFalse(dao.getTradeMessageDao().findById(message.getId()).orElseThrow().getIsRead());
        assertEquals(unreadBefore + 1, dao.getTradeMessageDao().countUnread(3));
        assertEquals(1, dao.getTradeMessageDao().findConversation(3, 4, 2).size());
        message.setContent("更新沟通"); dao.getTradeMessageDao().update(message);
        dao.getTradeMessageDao().markRead(3, 2);
        assertTrue(dao.getTradeMessageDao().findById(message.getId()).orElseThrow().getIsRead());
        assertEquals(1, dao.getTradeMessageDao().deleteById(message.getId()));
    }

    @Test void reviewCrudUniquenessAndAverageWork() {
        TradeFlow flow = flow(item("评价测试").getId());
        dao.getTradeFlowDao().updateStatus(flow.getId(), "DONE", null, LocalDateTime.now());
        Review review = new Review(); review.setFlowId(flow.getId()); review.setReviewerId(4);
        review.setRevieweeId(3); review.setScore(4); review.setContent("测试评价");
        dao.getReviewDao().insert(review);
        assertTrue(dao.getReviewDao().existsByFlowAndReviewer(flow.getId(), 4));
        assertThrows(DataAccessException.class, () -> dao.getReviewDao().insert(review));
        review.setScore(5); dao.getReviewDao().update(review);
        assertEquals(5, dao.getReviewDao().findById(review.getId()).orElseThrow().getScore());
        assertEquals(1, dao.getReviewDao().findByFlowId(flow.getId()).size());
        assertTrue(dao.getReviewDao().avgScoreByRevieweeId(3) > 0);
        assertEquals(1, dao.getReviewDao().deleteById(review.getId()));
    }

    @Test void creditLogPersistsSnapshotAndNullableFlow() {
        CreditLog log = new CreditLog(); log.setUserId(3); log.setDelta(-5); log.setScoreAfter(100); log.setReason("测试扣分");
        dao.getCreditLogDao().insert(log);
        CreditLog stored = dao.getCreditLogDao().findById(log.getId()).orElseThrow();
        assertNull(stored.getFlowId()); assertEquals(-5, stored.getDelta()); assertEquals(100, stored.getScoreAfter());
        stored.setReason("修订测试记录"); dao.getCreditLogDao().update(stored);
        assertEquals("修订测试记录", dao.getCreditLogDao().findByUserId(3, 1).get(0).getReason());
        assertEquals(1, dao.getCreditLogDao().deleteById(log.getId()));
    }

    @Test void operationLogIsAppendOnlyAtDaoBoundary() {
        OperationLog log = new OperationLog(); log.setOperation("TEST"); log.setDetail("测试日志");
        dao.getOperationLogDao().insert(log);
        assertNull(dao.getOperationLogDao().findById(log.getId()).orElseThrow().getOperatorId());
        assertEquals("TEST", dao.getOperationLogDao().findRecent(1).get(0).getOperation());
        assertThrows(UnsupportedOperationException.class, () -> dao.getOperationLogDao().update(log));
        assertThrows(UnsupportedOperationException.class, () -> dao.getOperationLogDao().deleteById(log.getId()));
        assertTrue(dao.getOperationLogDao().findById(log.getId()).isPresent());
    }

    @Test void configCrudDefaultsAndAtomicUpsertWork() {
        SystemConfig config = new SystemConfig(); config.setConfigKey("dao.test"); config.setConfigValue("12");
        dao.getSystemConfigDao().insert(config);
        assertEquals(12, dao.getSystemConfigDao().getInt("dao.test", 0));
        config.setConfigValue("invalid"); dao.getSystemConfigDao().update(config);
        assertEquals(77, dao.getSystemConfigDao().getInt("dao.test", 77));
        dao.getSystemConfigDao().upsert("dao.test", "1.25", "更新");
        assertEquals(1.25, dao.getSystemConfigDao().getDouble("dao.test", 0));
        assertNull(dao.getSystemConfigDao().getValue("missing"));
        assertEquals(1, dao.getSystemConfigDao().deleteById(config.getId()));
    }

    @Test void concurrentConfigUpsertsDoNotRaceOnUniqueKey() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Integer> task = () -> { start.await(); return dao.getSystemConfigDao().upsert("concurrent.key", "42", "并发测试"); };
            Future<Integer> first = workers.submit(task), second = workers.submit(task);
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS)); assertEquals(1, second.get(10, TimeUnit.SECONDS));
            assertEquals(1, scalar("SELECT COUNT(*) FROM system_config WHERE config_key='concurrent.key'"));
        } finally { workers.shutdownNow(); }
    }

    @ParameterizedTest(name = "数据库拒绝非法数据 {index}")
    @ValueSource(strings = {
        "UPDATE item SET price=-1 WHERE id=2",
        "UPDATE item SET trade_type='FREE', price=1 WHERE id=2",
        "UPDATE item SET status='INVALID' WHERE id=2",
        "UPDATE item SET owner_id=999999 WHERE id=2",
        "UPDATE item SET category_id=999999 WHERE id=2",
        "UPDATE item SET condition_level=11 WHERE id=2",
        "UPDATE user SET credit_score=151 WHERE id=3",
        "UPDATE user SET role='ROOT' WHERE id=3",
        "UPDATE user SET username='admin' WHERE id=3",
        "UPDATE trade_message SET is_read=2 WHERE id=1",
        "UPDATE review SET score=0 WHERE id=1",
        "UPDATE category SET enabled=2 WHERE id=1"
    })
    void schemaRejectsInvalidWrites(String illegalSql) {
        assertThrows(java.sql.SQLException.class, () -> sql(illegalSql));
    }
}
