package edu.campus.recycle.dao;

import edu.campus.recycle.entity.Item;
import edu.campus.recycle.entity.TradeFlow;
import edu.campus.recycle.exception.BusinessRuleException;
import edu.campus.recycle.support.DatabaseTestSupport;
import edu.campus.recycle.util.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 复用现有 Service 作为调用方，验证跨 DAO 的持久化与回滚；不修改业务实现。 */
class DataLayerIntegrationTest extends DatabaseTestSupport {
    @Test void completedTradePersistsItemFlowCreditAndAuditTogether() {
        var services = ServiceFactory.getInstance();
        Item item = item("完整流转数据层验证");
        services.getItemService().audit(1, item.getId(), true, "审核通过");
        int sellerBefore = dao.getUserDao().findById(3).orElseThrow().getCreditScore();
        int buyerBefore = dao.getUserDao().findById(4).orElseThrow().getCreditScore();
        int creditBefore = dao.getCreditLogDao().findAll().size();
        TradeFlow flow = services.getTradeService().apply(item.getId(), 4, "测试申请");
        services.getTradeService().agree(flow.getId(), 3);
        services.getTradeService().confirm(flow.getId(), 4);
        assertEquals("SOLD", dao.getItemDao().findById(item.getId()).orElseThrow().getStatus());
        assertEquals("DONE", dao.getTradeFlowDao().findById(flow.getId()).orElseThrow().getStatus());
        assertNotNull(dao.getTradeFlowDao().findById(flow.getId()).orElseThrow().getFinishedAt());
        assertEquals(sellerBefore + 2, dao.getUserDao().findById(3).orElseThrow().getCreditScore());
        assertEquals(buyerBefore + 2, dao.getUserDao().findById(4).orElseThrow().getCreditScore());
        assertEquals(creditBefore + 2, dao.getCreditLogDao().findAll().size());
        assertTrue(dao.getOperationLogDao().findAll().stream().anyMatch(log ->
                "FLOW_DONE".equals(log.getOperation()) && flow.getId().equals(log.getTargetId())));
        assertThrows(BusinessRuleException.class, () -> services.getTradeService().confirm(flow.getId(), 4));
    }

    @Test void lastListenerFailureRollsBackAlreadyWrittenCreditAndLogs() {
        var services = ServiceFactory.getInstance();
        Item item = item("事务故障注入验证");
        services.getItemService().audit(1, item.getId(), true, "审核通过");
        TradeFlow flow = services.getTradeService().apply(item.getId(), 4, "测试申请");
        services.getTradeService().agree(flow.getId(), 3);
        int creditCount = dao.getCreditLogDao().findAll().size();
        int logCount = dao.getOperationLogDao().findAll().size();
        int sellerCredit = dao.getUserDao().findById(3).orElseThrow().getCreditScore();
        int buyerCredit = dao.getUserDao().findById(4).orElseThrow().getCreditScore();
        ItemEventListener failure = event -> {
            if (event.getType() == ItemEvent.Type.FLOW_DONE) throw new IllegalStateException("最后监听器故障");
        };
        ItemEventBus.getInstance().register(failure);
        try {
            assertThrows(IllegalStateException.class, () -> services.getTradeService().confirm(flow.getId(), 4));
        } finally { ItemEventBus.getInstance().unregister(failure); }
        assertEquals("LOCKED", dao.getItemDao().findById(item.getId()).orElseThrow().getStatus());
        assertEquals("AGREED", dao.getTradeFlowDao().findById(flow.getId()).orElseThrow().getStatus());
        assertEquals(creditCount, dao.getCreditLogDao().findAll().size());
        assertEquals(logCount, dao.getOperationLogDao().findAll().size());
        assertEquals(sellerCredit, dao.getUserDao().findById(3).orElseThrow().getCreditScore());
        assertEquals(buyerCredit, dao.getUserDao().findById(4).orElseThrow().getCreditScore());
        services.getTradeService().confirm(flow.getId(), 4); // 故障移除后可正常提交
        assertEquals("DONE", dao.getTradeFlowDao().findById(flow.getId()).orElseThrow().getStatus());
    }

    @Test void publishFailureRollsBackBothItemAndImageRows() {
        Item candidate = item("发布事务候选");
        dao.getItemDao().deleteById(candidate.getId());
        candidate.setId(null);
        int itemCount = dao.getItemDao().findAll().size();
        int imageCount = dao.getItemImageDao().findAll().size();
        ItemEventListener failure = event -> {
            if (event.getType() == ItemEvent.Type.ITEM_PUBLISHED) throw new IllegalStateException("发布日志故障");
        };
        ItemEventBus.getInstance().register(failure);
        try {
            assertThrows(IllegalStateException.class, () -> ServiceFactory.getInstance().getItemService()
                    .publish(candidate, List.of("data/images/test.png")));
        } finally { ItemEventBus.getInstance().unregister(failure); }
        assertEquals(itemCount, dao.getItemDao().findAll().size());
        assertEquals(imageCount, dao.getItemImageDao().findAll().size());
    }
}
