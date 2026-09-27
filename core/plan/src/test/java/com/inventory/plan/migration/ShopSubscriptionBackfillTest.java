package com.inventory.plan.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.inventory.plan.service.ShopSubscriptionService;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShopSubscriptionBackfillTest {

  @Mock
  private ShopProvider shopProvider;

  @Mock
  private ShopSubscriptionService shopSubscriptionService;

  @InjectMocks
  private ShopSubscriptionBackfill backfill;

  @Test
  void visitsEveryShopAndContinuesPastFailures() {
    ShopInfo broken = new ShopInfo("broken", "p1", Instant.now());
    ShopInfo fine = new ShopInfo("fine", null, Instant.now());
    doAnswer(inv -> {
      Consumer<ShopInfo> action = inv.getArgument(0);
      action.accept(broken);
      action.accept(fine);
      return null;
    }).when(shopProvider).forEachShop(any());
    when(shopSubscriptionService.sync(broken, null)).thenThrow(new IllegalStateException("boom"));

    assertThat(backfill.run()).isEqualTo(2);
    verify(shopSubscriptionService, times(2)).sync(any(ShopInfo.class), isNull());
  }
}
