package com.inventory.plan.migration;

import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.ShopSubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates or refreshes a {@code shop_subscriptions} row for every shop from its plan fields.
 * Safe to re-run: unchanged shops are skipped.
 */
@Component
@Slf4j
public class ShopSubscriptionBackfill {

  @Autowired(required = false)
  private ShopProvider shopProvider;

  @Autowired
  private ShopSubscriptionService shopSubscriptionService;

  @Value("${plan.subscription.backfill-enabled:false}")
  private boolean backfillEnabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(30)
  public void backfillOnStartup() {
    if (!backfillEnabled || shopProvider == null) {
      return;
    }
    run();
  }

  public int run() {
    AtomicInteger visited = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    shopProvider.forEachShop(shop -> {
      visited.incrementAndGet();
      try {
        shopSubscriptionService.sync(shop, null);
      } catch (RuntimeException e) {
        failed.incrementAndGet();
        log.warn("Subscription backfill failed for shop {}: {}", shop.shopId(), e.getMessage());
      }
    });
    log.info("Shop subscription backfill visited {} shop(s), {} failed", visited.get(), failed.get());
    return visited.get();
  }
}
