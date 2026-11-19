package edu.campus.recycle.service.impl;

import edu.campus.recycle.config.DbConfig;
import edu.campus.recycle.dao.CategoryDao;
import edu.campus.recycle.dao.ItemDao;
import edu.campus.recycle.dao.ItemImageDao;
import edu.campus.recycle.dao.SystemConfigDao;
import edu.campus.recycle.dao.TradeFlowDao;
import edu.campus.recycle.dao.UserDao;
import edu.campus.recycle.dto.ItemQuery;
import edu.campus.recycle.dto.PageResult;
import edu.campus.recycle.entity.Category;
import edu.campus.recycle.entity.FlowStatus;
import edu.campus.recycle.entity.Item;
import edu.campus.recycle.entity.ItemImage;
import edu.campus.recycle.entity.ItemStatus;
import edu.campus.recycle.entity.Role;
import edu.campus.recycle.entity.TradeType;
import edu.campus.recycle.entity.User;
import edu.campus.recycle.entity.UserStatus;
import edu.campus.recycle.exception.BusinessRuleException;
import edu.campus.recycle.exception.EntityNotFoundException;
import edu.campus.recycle.exception.PermissionDeniedException;
import edu.campus.recycle.exception.ValidationException;
import edu.campus.recycle.service.ItemService;
import edu.campus.recycle.util.DbUtil;
import edu.campus.recycle.util.ItemEvent;
import edu.campus.recycle.util.ItemEventBus;
import edu.campus.recycle.util.Validator;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 物品服务实现。
 *
 * <p>发布物品与图片在同一事务内完成（BR-12 图片规则、BR-04 信用分门槛）；下架与审核
 * 保留操作痕迹并通过观察者写入操作日志。</p>
 */
public class ItemServiceImpl implements ItemService {

    private static final Logger LOG = Logger.getLogger(ItemServiceImpl.class.getName());

    /** 物品描述最大长度。 */
    public static final int DESCRIPTION_MAX = 500;
    private static final BigDecimal MAX_SEARCH_PRICE = BigDecimal.valueOf(100_000);

    private final ItemDao itemDao;
    private final ItemImageDao itemImageDao;
    private final CategoryDao categoryDao;
    private final UserDao userDao;
    private final TradeFlowDao tradeFlowDao;
    private final SystemConfigDao systemConfigDao;

    public ItemServiceImpl(ItemDao itemDao, ItemImageDao itemImageDao, CategoryDao categoryDao,
                           UserDao userDao, TradeFlowDao tradeFlowDao, SystemConfigDao systemConfigDao) {
        this.itemDao = Objects.requireNonNull(itemDao, "itemDao");
        this.itemImageDao = Objects.requireNonNull(itemImageDao, "itemImageDao");
        this.categoryDao = Objects.requireNonNull(categoryDao, "categoryDao");
        this.userDao = Objects.requireNonNull(userDao, "userDao");
        this.tradeFlowDao = Objects.requireNonNull(tradeFlowDao, "tradeFlowDao");
        this.systemConfigDao = Objects.requireNonNull(systemConfigDao, "systemConfigDao");
    }

    @Override
    public Item publish(Item item, List<String> imagePaths)
            throws ValidationException, BusinessRuleException {
        if (item == null) {
            throw invalidItem("物品信息不能为空");
        }
        List<String> paths = ItemImagePaths.validate(imagePaths, Path.of(""), Path.of(DbConfig.getInstance().getImageDir()));
        if (item.getOwnerId() == null) {
            throw invalidItem("发布者不能为空");
        }
        String status = item.getStatus() == null || item.getStatus().trim().isEmpty()
                ? ItemStatus.PENDING.getCode() : item.getStatus();
        if (!ItemStatus.PENDING.getCode().equals(status) && !ItemStatus.DRAFT.getCode().equals(status)) {
            throw invalidItem("发布状态只能为待审核或草稿");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DbUtil.getInstance().executeInTransaction(conn -> {
            User owner = requireActiveUser(item.getOwnerId());
            int minCredit = systemConfigDao.getInt("credit.min_publish", 60);
            if (owner.getCreditScore() != null && owner.getCreditScore() < minCredit) {
                throw BusinessRuleException.of("BR-04", "信用分低于 " + minCredit + "，暂不可发布物品");
            }
            validateItemFields(item);
            item.setStatus(status);
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            item.setPublishedAt(null);
            item.setAuditRemark(null);
            item.setViewCount(0);
            item.setFavoriteCount(0);
            itemDao.insert(item);
            if (!paths.isEmpty()) {
                List<ItemImage> images = new ArrayList<>(paths.size());
                for (int i = 0; i < paths.size(); i++) {
                    ItemImage image = new ItemImage();
                    image.setItemId(item.getId());
                    image.setFilePath(paths.get(i));
                    image.setSortOrder(i);
                    image.setCreatedAt(now);
                    images.add(image);
                }
                itemImageDao.batchInsert(images);
            }
            ItemEventBus.getInstance().publish(
                    ItemEvent.item(ItemEvent.Type.ITEM_PUBLISHED, item, item.getOwnerId(), "发布物品：" + item.getTitle()));
            return null;
        });
        LOG.info(() -> "物品发布成功：itemId=" + item.getId() + "，图片 " + paths.size() + " 张");
        return item;
    }

    @Override
    public void updateItem(Item item, int operatorId) throws PermissionDeniedException, EntityNotFoundException {
        if (item == null || item.getId() == null) {
            throw invalidItem("待更新物品不能为空");
        }
        DbUtil.getInstance().executeInTransaction(conn -> {
            Item existing = itemDao.findById(item.getId())
                    .orElseThrow(() -> EntityNotFoundException.ofCode("NF-011", "物品不存在"));
            if (!Objects.equals(existing.getOwnerId(), operatorId)) {
                throw PermissionDeniedException.ofCode("PERM-011", "仅物品发布者可修改该物品内容");
            }
            requireActiveUser(operatorId);
            if (ItemStatus.SOLD.getCode().equals(existing.getStatus())
                    || ItemStatus.LOCKED.getCode().equals(existing.getStatus())) {
                throw BusinessRuleException.of("BR-05", "已成交或被锁定的物品不可修改");
            }
            validateItemFields(item);
            item.setOwnerId(existing.getOwnerId());
            item.setStatus(existing.getStatus());
            item.setAuditRemark(existing.getAuditRemark());
            item.setPublishedAt(existing.getPublishedAt());
            item.setViewCount(existing.getViewCount());
            item.setFavoriteCount(existing.getFavoriteCount());
            item.setUpdatedAt(LocalDateTime.now().withNano(0));
            itemDao.update(item);
            ItemEventBus.getInstance().publish(ItemEvent.item(ItemEvent.Type.ITEM_UPDATED, item,
                    operatorId, "修改物品：" + item.getTitle()));
            return null;
        });
    }

    @Override
    public void offShelf(int itemId, int operatorId) throws PermissionDeniedException {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DbUtil.getInstance().executeInTransaction(conn -> {
            Item item = itemDao.findById(itemId)
                    .orElseThrow(() -> EntityNotFoundException.ofCode("NF-011", "物品不存在"));
            User operator = requireActiveUser(operatorId);
            if (!Role.ADMIN.getCode().equals(operator.getRole()) && !Objects.equals(item.getOwnerId(), operatorId)) {
                throw PermissionDeniedException.ofCode("PERM-012", "仅物品发布者或管理员可下架该物品");
            }
            if (ItemStatus.SOLD.getCode().equals(item.getStatus())
                    || ItemStatus.OFF_SHELF.getCode().equals(item.getStatus())) {
                throw BusinessRuleException.of("BR-10", "当前状态不可下架：" + item.getStatus());
            }
            tradeFlowDao.findActiveByItemId(itemId).ifPresent(flow ->
                    tradeFlowDao.updateStatus(flow.getId(), FlowStatus.CANCELLED.getCode(), "物品下架自动取消", now));
            itemDao.updateStatus(itemId, ItemStatus.OFF_SHELF.getCode(), "操作人下架");
            ItemEventBus.getInstance().publish(
                    ItemEvent.of(ItemEvent.Type.ITEM_OFF_SHELF, itemId, null, operatorId, "下架物品：" + item.getTitle()));
            return null;
        });
    }

    @Override
    public PageResult<Item> search(ItemQuery query) {
        ItemQuery effective = query == null ? new ItemQuery() : query;
        validateSearchRange(effective);
        if (effective.getCategoryId() != null) {
            requireEnabledCategory(effective.getCategoryId());
        }
        List<Item> records = itemDao.search(effective);
        long total = itemDao.countSearch(effective);
        return PageResult.of(records, total, effective.getPageNo(), effective.getLimit());
    }

    @Override
    public Item detail(int itemId) throws EntityNotFoundException {
        Item item = itemDao.findById(itemId)
                .orElseThrow(() -> EntityNotFoundException.of("物品不存在：id=" + itemId));
        itemDao.increaseViewCount(itemId);
        item.setViewCount((item.getViewCount() == null ? 0 : item.getViewCount()) + 1);
        return item;
    }

    @Override
    public void audit(int operatorId, int itemId, boolean pass, String remark) throws PermissionDeniedException {
        requireAdmin(operatorId);
        Item item = itemDao.findById(itemId)
                .orElseThrow(() -> EntityNotFoundException.of("物品不存在：id=" + itemId));
        if (ItemStatus.SOLD.getCode().equals(item.getStatus())) {
            throw BusinessRuleException.of("BR-10", "已成交物品不可再审核");
        }
        if (!ItemStatus.PENDING.getCode().equals(item.getStatus())
                && !ItemStatus.DRAFT.getCode().equals(item.getStatus())) {
            throw BusinessRuleException.of("BR-10", "仅待审核状态可审核，当前：" + item.getStatus());
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        DbUtil.getInstance().executeInTransaction(conn -> {
            if (pass) {
                item.setStatus(ItemStatus.ON_SALE.getCode());
                item.setAuditRemark(remark);
                item.setUpdatedAt(now);
                if (item.getPublishedAt() == null) {
                    item.setPublishedAt(now);
                }
                itemDao.update(item);
            } else {
                itemDao.updateStatus(itemId, ItemStatus.REJECTED.getCode(), remark);
            }
            ItemEventBus.getInstance().publish(
                    ItemEvent.of(ItemEvent.Type.ITEM_AUDITED, itemId, null, operatorId,
                            (pass ? "审核通过：" : "审核驳回：") + item.getTitle()));
            return null;
        });
    }

    private void validateItemFields(Item item) {
        if (!Validator.isValidTitle(item.getTitle())) {
            throw invalidItem("标题长度必须为 2–50 字");
        }
        if (item.getDescription() == null) {
            item.setDescription("");
        }
        if (item.getDescription().length() > DESCRIPTION_MAX) {
            throw invalidItem("物品描述不能超过 " + DESCRIPTION_MAX + " 字");
        }
        requireEnabledCategory(item.getCategoryId());
        String type = item.getTradeType() == null || item.getTradeType().isBlank()
                ? TradeType.SELL.getCode() : item.getTradeType().strip().toUpperCase(Locale.ROOT);
        if (!TradeType.SELL.getCode().equals(type) && !TradeType.EXCHANGE.getCode().equals(type)
                && !TradeType.FREE.getCode().equals(type)) {
            throw invalidItem("流转方式无效");
        }
        item.setTradeType(type);
        if (item.getPrice() == null) {
            item.setPrice(BigDecimal.ZERO);
        }
        if (item.getPrice().signum() < 0 || (item.getOriginalPrice() != null && item.getOriginalPrice().signum() < 0)) {
            throw invalidItem("价格不能为负数");
        }
        if (TradeType.FREE.getCode().equals(type) && item.getPrice().signum() != 0) {
            throw ValidationException.ofCode("VAL-004", "赠送物品的价格必须为 0 元");
        }
        if (item.getConditionLevel() != null && (item.getConditionLevel() < 1 || item.getConditionLevel() > 10)) {
            throw invalidItem("成色必须在 1–10 之间");
        }
        if (item.getTags() != null && item.getTags().split(",", -1).length > 5) {
            throw invalidItem("物品最多填写 5 个标签");
        }
    }

    private void requireEnabledCategory(Integer categoryId) {
        if (categoryId == null) {
            throw invalidItem("请选择物品分类");
        }
        if (!isCategoryEnabled(categoryId)) {
            throw EntityNotFoundException.ofCode("NF-002", "所选分类不存在或已停用");
        }
    }

    private boolean isCategoryEnabled(int id) {
        return categoryDao.findById(id).filter(category -> Boolean.TRUE.equals(category.getEnabled()))
                .filter(category -> category.getParentId() == null || categoryDao.findById(category.getParentId())
                        .map(Category::getEnabled).orElse(false))
                .isPresent();
    }

    private static void validateSearchRange(ItemQuery query) {
        BigDecimal min = query.getMinPrice();
        BigDecimal max = query.getMaxPrice();
        if (isInvalidSearchPrice(min) || isInvalidSearchPrice(max) || (min != null && max != null && min.compareTo(max) > 0)) {
            throw ValidationException.ofCode("VAL-005", "价格区间不正确");
        }
        Integer condition = query.getConditionMin();
        if (condition != null && (condition < 1 || condition > 10)) {
            throw ValidationException.ofCode("VAL-005", "成色下限必须在 1–10 之间");
        }
    }

    private static boolean isInvalidSearchPrice(BigDecimal price) {
        return price != null && (price.signum() < 0 || price.compareTo(MAX_SEARCH_PRICE) > 0);
    }

    private static ValidationException invalidItem(String message) {
        return ValidationException.ofCode("VAL-011", message);
    }

    private User requireActiveUser(int id) {
        User user = userDao.findById(id).orElseThrow(() -> EntityNotFoundException.of("用户不存在"));
        if (!UserStatus.ACTIVE.getCode().equals(user.getStatus())) {
            throw PermissionDeniedException.of("账号当前不可操作");
        }
        return user;
    }

    private User requireAdmin(int operatorId) throws PermissionDeniedException {
        User operator = userDao.findById(operatorId)
                .orElseThrow(() -> EntityNotFoundException.of("操作用户不存在：id=" + operatorId));
        if (!Role.ADMIN.getCode().equals(operator.getRole())) {
            throw PermissionDeniedException.of("仅管理员可执行该操作");
        }
        return operator;
    }
}
