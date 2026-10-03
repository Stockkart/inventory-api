package com.inventory.product.migration;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.service.LotPackaging;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * One-off, idempotent repair for lots whose stored base counts don't match display × the pack
 * factor of their catalog product. This happened when a lot was registered with packaging that
 * differed from the product already owning its barcode: base counts used the request's factor
 * while every read hydrates the product's.
 *
 * <p>Display counts are what the user entered and every stock movement updates both in step, so
 * base counts are re-derived from them. Runs on startup only when
 * {@code stockkart.lot-base-count-repair.enabled=true}; defaults to dry-run (log only).
 */
@Component
@Slf4j
public class LotBaseCountRepairRunner {

  @Autowired private InventoryRepository inventoryRepository;
  @Autowired private MongoTemplate mongoTemplate;

  @Value("${stockkart.lot-base-count-repair.enabled:false}")
  private boolean enabled;

  @Value("${stockkart.lot-base-count-repair.dry-run:true}")
  private boolean dryRun;

  @EventListener(ApplicationReadyEvent.class)
  @Order(30)
  public void run() {
    if (!enabled) {
      return;
    }
    List<String> shopIds = mongoTemplate.findDistinct(
        Query.query(Criteria.where("productId").ne(null)), "shopId", Inventory.class, String.class);
    log.info("[lot-base-repair] starting (dryRun={}) across {} shop(s)", dryRun, shopIds.size());
    long total = 0;
    for (String shopId : shopIds) {
      if (StringUtils.hasText(shopId)) {
        total += repairShop(shopId);
      }
    }
    log.info("[lot-base-repair] done (dryRun={}): {} lot(s) {}",
        dryRun, total, dryRun ? "would be repaired" : "repaired");
  }

  private long repairShop(String shopId) {
    // findByShopId goes through the read aspect, so packaging is the product's.
    List<Inventory> lots = inventoryRepository.findByShopId(shopId);
    long repaired = 0;
    for (Inventory lot : lots) {
      if (lot == null || !StringUtils.hasText(lot.getProductId())) {
        continue;
      }
      int factor = LotPackaging.factor(lot.getUnitConversions());
      boolean drifted =
          LotPackaging.isBaseCountDrifted(lot.getReceivedBaseCount(), lot.getReceivedCount(), factor)
              || LotPackaging.isBaseCountDrifted(lot.getCurrentBaseCount(), lot.getCurrentCount(), factor)
              || LotPackaging.isBaseCountDrifted(lot.getSoldBaseCount(), lot.getSoldCount(), factor);
      if (!drifted) {
        continue;
      }
      int received = LotPackaging.toBase(lot.getReceivedCount(), factor);
      int current = LotPackaging.toBase(lot.getCurrentCount(), factor);
      int sold = LotPackaging.toBase(lot.getSoldCount(), factor);
      log.info(
          "[lot-base-repair] shop {} lot {} ({}, packaging {}): base received/current/sold "
              + "{}/{}/{} -> {}/{}/{}{}",
          shopId,
          lot.getId(),
          lot.getName(),
          LotPackaging.describe(lot.getBaseUnit(), lot.getUnitConversions()),
          lot.getReceivedBaseCount(),
          lot.getCurrentBaseCount(),
          lot.getSoldBaseCount(),
          received,
          current,
          sold,
          dryRun ? " (dry-run)" : "");
      if (!dryRun) {
        // $set only the base counts; saving the hydrated entity would rewrite the whole document.
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(lot.getId()).and("shopId").is(shopId)),
            new Update()
                .set("receivedBaseCount", received)
                .set("currentBaseCount", current)
                .set("soldBaseCount", sold),
            Inventory.class);
      }
      repaired++;
    }
    return repaired;
  }
}
