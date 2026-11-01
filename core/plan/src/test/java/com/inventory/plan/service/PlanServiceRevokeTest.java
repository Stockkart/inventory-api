package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PlanServiceRevokeTest {

  private static final Instant FUTURE = Instant.now().plus(Duration.ofDays(200));
  private static final Instant LATER = Instant.now().plus(Duration.ofDays(365));

  @Mock private ShopProvider shopProvider;
  @Mock private PlanTransactionRepository planTransactionRepository;
  @Mock private ShopSubscriptionService shopSubscriptionService;
  @Mock private EntitlementService entitlementService;

  @InjectMocks
  private PlanService planService;

  private PlanPaymentOrder order;
  private PlanTransaction refundedTerm;

  @BeforeEach
  void setUp() {
    order = new PlanPaymentOrder();
    order.setId("order-2");
    order.setShopId("shop-1");
    refundedTerm = term("tx-2", "order-2", "growth", Instant.now(), LATER);
    when(planTransactionRepository.findFirstByPaymentOrderId("order-2")).thenReturn(Optional.of(refundedTerm));
  }

  private static PlanTransaction term(String id, String orderId, String planId, Instant createdAt, Instant endsAt) {
    PlanTransaction tx = new PlanTransaction();
    tx.setId(id);
    tx.setPaymentOrderId(orderId);
    tx.setPlanId(planId);
    tx.setCreatedAt(createdAt);
    tx.setTermEndsAt(endsAt);
    return tx;
  }

  @Test
  void fallsBackToTheEarlierTermThatHasNotEnded() {
    PlanTransaction earlier = term("tx-1", "order-1", "starter", Instant.now().minus(Duration.ofDays(30)), FUTURE);
    when(shopProvider.getShop("shop-1")).thenReturn(Optional.of(new ShopInfo("shop-1", "growth", LATER)));
    when(planTransactionRepository.findByShopId(eq("shop-1"), any(Sort.class)))
        .thenReturn(List.of(refundedTerm, earlier));

    planService.revokeForOrder(order);

    verify(shopProvider).updatePlan("shop-1", "starter", FUTURE);
    verify(shopSubscriptionService).sync(new ShopInfo("shop-1", "starter", FUTURE), "order-1");
    assertThat(refundedTerm.getRefundedAt()).isNotNull();
    verify(planTransactionRepository).save(refundedTerm);
    verify(entitlementService).invalidate("shop-1");
  }

  @Test
  void expiresThePlanNowWithoutAnEarlierTerm() {
    when(shopProvider.getShop("shop-1")).thenReturn(Optional.of(new ShopInfo("shop-1", "growth", LATER)));
    when(planTransactionRepository.findByShopId(eq("shop-1"), any(Sort.class))).thenReturn(List.of(refundedTerm));

    planService.revokeForOrder(order);

    ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
    verify(shopProvider).updatePlan(eq("shop-1"), eq("growth"), expiry.capture());
    assertThat(expiry.getValue()).isBefore(Instant.now().plusSeconds(1));
  }

  @Test
  void leavesALaterPurchaseAlone() {
    when(shopProvider.getShop("shop-1"))
        .thenReturn(Optional.of(new ShopInfo("shop-1", "enterprise", LATER.plus(Duration.ofDays(30)))));

    planService.revokeForOrder(order);

    verify(shopProvider, never()).updatePlan(anyString(), anyString(), any());
    assertThat(refundedTerm.getRefundedAt()).isNotNull();
  }

  @Test
  void aTermAlreadyRevokedIsNotTouchedAgain() {
    refundedTerm.setRefundedAt(Instant.now());

    planService.revokeForOrder(order);

    verify(shopProvider, never()).getShop(anyString());
    verify(planTransactionRepository, never()).save(any());
  }
}
