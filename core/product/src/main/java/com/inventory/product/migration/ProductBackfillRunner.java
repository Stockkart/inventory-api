package com.inventory.product.migration;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One-off, idempotent backfill that gives every legacy {@link Inventory} row a {@code productId} by
 * grouping rows into shop-scoped catalog {@link com.inventory.product.domain.model.Product}s.
 *
 * <p>Runs on startup only when {@code stockkart.product-backfill.enabled=true}. Defaults to
 * {@code dry-run=true} which logs what would happen without writing. Safe to re-run: rows already
 * linked are skipped and product resolution reuses existing catalog products, so a crash mid-run
 * simply resumes on the next run.
 *
 * <p>Identity fields are {@code @Transient} on {@link Inventory}, so this runner reads legacy
 * identity directly from the raw Mongo document (if still present) instead of relying on the
 * repository entity mapping.
 */
@Component
@Slf4j
public class ProductBackfillRunner {

  @Autowired private InventoryRepository inventoryRepository;
  @Autowired private ProductService productService;
  @Autowired private MongoTemplate mongoTemplate;

  @Value("${stockkart.product-backfill.enabled:false}")
  private boolean enabled;

  @Value("${stockkart.product-backfill.dry-run:true}")
  private boolean dryRun;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void run() {
    if (!enabled) {
      return;
    }
    List<String> shopIds = mongoTemplate.findDistinct(
        Query.query(Criteria.where("productId").is(null)),
        "shopId",
        Inventory.class,
        String.class);

    log.info("[product-backfill] starting (dryRun={}) across {} shop(s)", dryRun, shopIds.size());
    long totalLinked = 0;
    long totalProducts = 0;
    for (String shopId : shopIds) {
      if (!StringUtils.hasText(shopId)) {
        continue;
      }
      long[] shopResult = processShop(shopId);
      totalLinked += shopResult[0];
      totalProducts += shopResult[1];
    }
    log.info("[product-backfill] done (dryRun={}): linked {} inventory row(s) to {} product(s)",
        dryRun, totalLinked, totalProducts);
  }

  /** @return [linkedRowCount, distinctProductCount] for the shop. */
  private long[] processShop(String shopId) {
    List<Inventory> rows = loadUnlinkedWithLegacyIdentity(shopId);
    if (rows.isEmpty()) {
      return new long[] {0, 0};
    }

    if (dryRun) {
      Set<String> keys = new HashSet<>();
      for (Inventory inv : rows) {
        keys.add(productService.identityKey(inv, shopId));
      }
      log.info("[product-backfill] shop {}: {} row(s) -> {} product(s) (dry-run)",
          shopId, rows.size(), keys.size());
      return new long[] {rows.size(), keys.size()};
    }

    Set<String> productIds = new HashSet<>();
    long linked = 0;
    long skipped = 0;
    for (Inventory inv : rows) {
      if (!StringUtils.hasText(inv.getName())) {
        log.warn(
            "[product-backfill] shop {}: skipping inventory {} — no identity in Mongo document "
                + "(field may have been removed already; re-register or repair manually)",
            shopId,
            inv.getId());
        skipped++;
        continue;
      }
      String productId = productService.resolveForRegistration(null, inv, shopId);
      inv.setProductId(productId);
      inventoryRepository.save(inv);
      productIds.add(productId);
      linked++;
    }
    if (skipped > 0) {
      log.warn("[product-backfill] shop {}: skipped {} row(s) with missing identity", shopId, skipped);
    }
    log.info("[product-backfill] shop {}: linked {} row(s) to {} product(s)",
        shopId, linked, productIds.size());
    return new long[] {linked, productIds.size()};
  }

  /**
   * Loads rows with {@code productId == null} and hydrates catalog identity from the raw BSON
   * document. Needed because identity is {@code @Transient} on the entity and is not mapped by
   * {@link InventoryRepository} even when legacy fields still exist in Mongo.
   */
  private List<Inventory> loadUnlinkedWithLegacyIdentity(String shopId) {
    Query query = Query.query(
        Criteria.where("shopId").is(shopId).and("productId").is(null));
    List<Document> docs =
        mongoTemplate.find(query, Document.class, mongoTemplate.getCollectionName(Inventory.class));
    List<Inventory> rows = new ArrayList<>(docs.size());
    for (Document doc : docs) {
      Inventory inv = new Inventory();
      inv.setId(readDocumentId(doc));
      inv.setShopId(shopId);
      applyLegacyIdentityFromDocument(inv, doc);
      rows.add(inv);
    }
    return rows;
  }

  private static String readDocumentId(Document doc) {
    Object id = doc.get("_id");
    if (id instanceof ObjectId objectId) {
      return objectId.toHexString();
    }
    return id != null ? id.toString() : null;
  }

  private static void applyLegacyIdentityFromDocument(Inventory inv, Document doc) {
    inv.setBarcode(doc.getString("barcode"));
    inv.setName(doc.getString("name"));
    inv.setDescription(doc.getString("description"));
    inv.setCompanyName(doc.getString("companyName"));
    inv.setBusinessType(doc.getString("businessType"));
    inv.setHsn(doc.getString("hsn"));
    inv.setBaseUnit(doc.getString("baseUnit"));
    inv.setItemType(readItemType(doc.get("itemType")));
    inv.setItemTypeDegree(readInteger(doc.get("itemTypeDegree")));
    inv.setUnitConversions(readUnitConversion(doc.get("unitConversions")));
  }

  private static ItemType readItemType(Object raw) {
    if (raw == null) {
      return null;
    }
    try {
      return ItemType.valueOf(String.valueOf(raw));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Integer readInteger(Object raw) {
    if (raw == null) {
      return null;
    }
    if (raw instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(raw));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static UnitConversion readUnitConversion(Object raw) {
    if (!(raw instanceof Document doc)) {
      return null;
    }
    String unit = doc.getString("unit");
    Integer factor = readInteger(doc.get("factor"));
    if (!StringUtils.hasText(unit) || factor == null || factor <= 0) {
      return null;
    }
    return new UnitConversion(unit, factor);
  }
}
