import edu.campus.recycle.config.DatabaseInitializer;
import edu.campus.recycle.dto.ItemQuery;
import edu.campus.recycle.entity.Item;
import edu.campus.recycle.util.DaoFactory;
import edu.campus.recycle.util.DbUtil;
import edu.campus.recycle.util.ServiceFactory;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/** 在指定临时库重现走查问题；不修改 Service/GUI 源码，不使用正式数据库。 */
public final class MemberAdviceProbe {
    private static Item candidate(String title) {
        Item item = new Item(); item.setTitle(title); item.setDescription("走查探针");
        item.setOwnerId(3); item.setCategoryId(6); item.setTradeType("SELL"); item.setPrice(BigDecimal.ONE);
        return item;
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("必须传入空的临时目录");
        Path database = Path.of(args[0]).resolve("member-advice.db");
        if (java.nio.file.Files.exists(database)) throw new IllegalArgumentException("探针数据库已存在，请使用新的临时目录");
        DbUtil.getInstance().init("jdbc:sqlite:" + database.toAbsolutePath());
        DatabaseInitializer.initialize(true);
        var services = ServiceFactory.getInstance();
        var dao = DaoFactory.getInstance();
        var registered = services.getUserService().register("probe_admin", "Probe12345", "走查账号",
                "20990001", "ADMIN", null, null);
        System.out.println("FINDING M2-01 publicRegistrationRole=" + registered.getRole());
        var search = services.getItemService().search(new ItemQuery());
        System.out.println("FINDING M2-02 defaultSearchNonPublicStates=" + search.getRecords().stream()
                .map(Item::getStatus).filter(status -> !"ON_SALE".equals(status)).distinct().sorted().toList());
        dao.getUserDao().updateStatus(3, "FROZEN");
        Item frozen = services.getItemService().publish(candidate("冻结用户发布探针"), List.of());
        System.out.println("FINDING M2-03 frozenUserPublishedItem=" + frozen.getId());
        dao.getUserDao().updateStatus(3, "ACTIVE");
        Item image = services.getItemService().publish(candidate("图片路径探针"), List.of("C:/missing/probe.png"));
        System.out.println("FINDING M2-04 persistedImagePath=" + dao.getItemImageDao().findByItemId(image.getId()).get(0).getFilePath());
        ItemQuery query = new ItemQuery(); query.setPageSize(0);
        var page = services.getItemService().search(query);
        System.out.println("FINDING M2-05 pageSize=" + page.getPageSize() + ", actualRecords=" + page.getRecords().size());
    }
}
