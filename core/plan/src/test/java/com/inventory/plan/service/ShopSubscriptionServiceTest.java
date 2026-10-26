package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.ShopSubscription;
import com.inventory.plan.domain.model.SubscriptionStatus;
import com.inventory.plan.domain.repository.ShopSubscriptionRepository;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class ShopSubscriptionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");
  private static final Instant FUTURE = Instant.now().plus(Duration.ofDays(30));
  private static final Instant PAST = Instant.now().minus(Duration.ofDays(1));

  @Mock
  private ShopSubscriptionRepository repository;

  @Mock
  private MongoTemplate mongoTemplate;

  @InjectMocks
  private ShopSubscriptionService service;

  @Test
  void deriveStatusMatchesShopPlanStatusRules() {
    assertThat(ShopSubscriptionService.deriveStatus(null, NOW.plusSeconds(60), NOW)).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(ShopSubscriptionService.deriveStatus("", null, NOW)).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(ShopSubscriptionService.deriveStatus("p1", NOW.plusSeconds(60), NOW)).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(ShopSubscriptionService.deriveStatus("p1", NOW, NOW)).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(ShopSubscriptionService.deriveStatus("p1", NOW.minusSeconds(1), NOW)).isEqualTo(SubscriptionStatus.EXPIRED);
    assertThat(ShopSubscriptionService.deriveStatus(null, NOW.minusSeconds(1), NOW)).isEqualTo(SubscriptionStatus.EXPIRED);
  }

  @Test
  void createsSubscriptionKeyedByShopId() {
    when(repository.findById("shop-1")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ShopSubscription saved = service.sync(new ShopInfo("shop-1", "plan-1", FUTURE), "order-1");

    assertThat(saved.getId()).isEqualTo("shop-1");
    assertThat(saved.getShopId()).isEqualTo("shop-1");
    assertThat(saved.getPlanId()).isEqualTo("plan-1");
    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(saved.getExpiresAt()).isEqualTo(FUTURE);
    assertThat(saved.getSourceOrderId()).isEqualTo("order-1");
    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void trialShopGetsTrialSubscriptionWithNullPlan() {
    when(repository.findById("shop-1")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ShopSubscription saved = service.sync(new ShopInfo("shop-1", null, FUTURE), null);

    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.TRIAL);
    assertThat(saved.getPlanId()).isNull();
  }

  @Test
  void skipsWriteWhenNothingChanged() {
    ShopSubscription current = existing("plan-1", SubscriptionStatus.ACTIVE, FUTURE, "order-1");
    when(repository.findById("shop-1")).thenReturn(Optional.of(current));

    assertThat(service.sync(new ShopInfo("shop-1", "plan-1", FUTURE), null)).isSameAs(current);
    assertThat(service.sync(new ShopInfo("shop-1", "plan-1", FUTURE), "order-1")).isSameAs(current);
    verify(repository, never()).save(any());
  }

  @Test
  void renewalUpdatesInPlaceAndKeepsCreatedAt() {
    ShopSubscription current = existing("plan-1", SubscriptionStatus.EXPIRED, PAST, "order-1");
    Instant createdAt = current.getCreatedAt();
    when(repository.findById("shop-1")).thenReturn(Optional.of(current));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ShopSubscription saved = service.sync(new ShopInfo("shop-1", "plan-2", FUTURE), "order-2");

    assertThat(saved.getId()).isEqualTo("shop-1");
    assertThat(saved.getPlanId()).isEqualTo("plan-2");
    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    assertThat(saved.getExpiresAt()).isEqualTo(FUTURE);
    assertThat(saved.getSourceOrderId()).isEqualTo("order-2");
    assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
  }

  @Test
  void syncWithoutOrderKeepsPreviousSourceOrder() {
    ShopSubscription current = existing("plan-1", SubscriptionStatus.ACTIVE, PAST, "order-1");
    when(repository.findById("shop-1")).thenReturn(Optional.of(current));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ShopSubscription saved = service.sync(new ShopInfo("shop-1", "plan-1", PAST), null);

    assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
    assertThat(saved.getSourceOrderId()).isEqualTo("order-1");
  }

  @Test
  void expireLapsedIsOneConditionalUpdateOnTrialAndActiveOnly() {
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(ShopSubscription.class)))
        .thenReturn(UpdateResult.acknowledged(3, 3L, null));

    assertThat(service.expireLapsed(NOW)).isEqualTo(3);

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateMulti(query.capture(), update.capture(), eq(ShopSubscription.class));
    Document filter = query.getValue().getQueryObject();
    assertThat(((Document) filter.get("status")).get("$in"))
        .asList().containsExactlyInAnyOrder(SubscriptionStatus.TRIAL, SubscriptionStatus.ACTIVE);
    assertThat(((Document) filter.get("expiresAt")).get("$lt")).isEqualTo(NOW);
    assertThat(((Document) update.getValue().getUpdateObject().get("$set")).get("status"))
        .isEqualTo(SubscriptionStatus.EXPIRED);
  }

  private static ShopSubscription existing(String planId, SubscriptionStatus status, Instant expiresAt, String orderId) {
    return ShopSubscription.builder()
        .id("shop-1")
        .shopId("shop-1")
        .planId(planId)
        .status(status)
        .expiresAt(expiresAt)
        .sourceOrderId(orderId)
        .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
        .build();
  }
}
