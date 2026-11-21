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
import com.inventory.plan.api.port.ShopPort.ShopInfo;
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

  @Mock
  private EntitlementService entitlementService;

  @Mock
  private com.inventory.plan.domain.repository.PlanRepository planRepository;

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
    verify(entitlementService, never()).invalidate(any());
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
    verify(entitlementService).invalidate("shop-1");
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
    verify(entitlementService).invalidateAll();

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

  @Test
  void aGrantIsOneUpsertThatNeverMovesTheEndBackwards() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(ShopSubscription.class)))
        .thenAnswer(inv -> existing("plan-1", SubscriptionStatus.ACTIVE, FUTURE, "order-2"));

    ShopSubscription saved = service.recordGrant(new ShopInfo("shop-1", "plan-1", FUTURE), "order-2");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(query.capture(), update.capture(),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(ShopSubscription.class));
    Document filter = query.getValue().getQueryObject();
    assertThat(filter.get("_id")).isEqualTo("shop-1");
    assertThat(filter.toString()).contains("$or").contains("$lte");
    org.bson.Document set = (org.bson.Document) update.getValue().getUpdateObject().get("$set");
    assertThat(set).containsEntry("planId", "plan-1").containsEntry("sourceOrderId", "order-2");
    assertThat(saved.getSourceOrderId()).isEqualTo("order-2");
    verify(repository, never()).save(any());
  }

  @Test
  void aStaleGrantLeavesTheLaterTermInPlace() {
    ShopSubscription later = existing("plan-1", SubscriptionStatus.ACTIVE, FUTURE.plusSeconds(3600), "order-3");
    when(repository.findById("shop-1")).thenReturn(Optional.of(later));

    assertThat(service.recordGrant(new ShopInfo("shop-1", "plan-1", FUTURE), "order-2")).isSameAs(later);
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(ShopSubscription.class));
  }

  @Test
  void aGrantThatLosesARaceOutsideATransactionKeepsTheLaterTerm() {
    ShopSubscription later = existing("plan-1", SubscriptionStatus.ACTIVE, FUTURE.plusSeconds(3600), "order-3");
    when(repository.findById("shop-1")).thenReturn(Optional.empty(), Optional.of(later));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(ShopSubscription.class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("E11000"));

    assertThat(service.recordGrant(new ShopInfo("shop-1", "plan-1", FUTURE), "order-2")).isSameAs(later);
  }

  @Test
  void aGrantOfAnotherPlanReplacesTheTerm() {
    when(repository.findById("shop-1")).thenReturn(
        Optional.of(existing("plan-1", SubscriptionStatus.ACTIVE, FUTURE.plusSeconds(3600), "order-3")));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(ShopSubscription.class)))
        .thenAnswer(inv -> existing("plan-2", SubscriptionStatus.ACTIVE, FUTURE, "order-4"));

    assertThat(service.recordGrant(new ShopInfo("shop-1", "plan-2", FUTURE), "order-4").getPlanId())
        .isEqualTo("plan-2");
  }

  @Test
  void aGrantKeepsTheGrantedPlansFeaturesAndLimits() {
    com.inventory.plan.domain.model.Plan pro = EntitlementServiceTest.plan(
        "plan-1", "PROFESSIONAL", java.util.Set.of(com.inventory.common.entitlement.PlanFeature.ACCOUNTING), 5, 100);
    when(planRepository.findById("plan-1")).thenReturn(Optional.of(pro));

    service.recordGrant(new ShopInfo("shop-1", "plan-1", FUTURE), "order-2");

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(any(Query.class), update.capture(),
        any(org.springframework.data.mongodb.core.FindAndModifyOptions.class), eq(ShopSubscription.class));
    var terms = (com.inventory.plan.domain.model.PlanTermsSnapshot)
        ((Document) update.getValue().getUpdateObject().get("$set")).get("terms");
    assertThat(terms.getPlanCode()).isEqualTo("PROFESSIONAL");
    assertThat(terms.getUserLimit()).isEqualTo(5);
    assertThat(terms.getOcrLimit()).isEqualTo(100);
    verify(entitlementService).invalidate("shop-1");
  }
}
