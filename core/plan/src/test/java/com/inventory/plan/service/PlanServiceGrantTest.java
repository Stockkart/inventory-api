package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.tx.MongoTx;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.api.port.ShopPort;
import com.inventory.plan.api.port.ShopPort.ShopInfo;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.mapper.PlanTransactionMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * Granting a paid order: one transaction per order inside MongoTx, renewal of the same plan extends
 * from the current expiry, and a different plan is refused while paid days remain (A2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanServiceGrantTest {

  @Mock private PlanRepository planRepository;
  @Mock private ShopPort shopProvider;
  @Mock private PlanTransactionRepository planTransactionRepository;
  @Mock private PlanTransactionMapper planTransactionMapper;
  @Mock private ShopSubscriptionService shopSubscriptionService;
  @Mock private MetricsWrapper metrics;
  @Mock private MongoTx mongoTx;

  @InjectMocks
  private PlanService planService;

  private final Plan pro = plan("pro-id", "PROFESSIONAL");
  private final Plan starter = plan("starter-id", "STARTER");

  @BeforeEach
  void setUp() {
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(2)).run();
      return null;
    }).when(mongoTx).run(eq(MongoTx.PLAN_GRANT), anyString(), any(Runnable.class));
    when(planRepository.findById("pro-id")).thenReturn(Optional.of(pro));
    when(planRepository.findById("starter-id")).thenReturn(Optional.of(starter));
    when(planTransactionMapper.toTransaction(any(), any(), any(), any())).thenAnswer(inv -> {
      PlanTransaction tx = new PlanTransaction();
      tx.setTermStartsAt(inv.getArgument(2));
      tx.setTermEndsAt(inv.getArgument(3));
      return tx;
    });
  }

  @Test
  void aRenewalOfTheSamePlanStartsAtTheCurrentExpiry() {
    Instant expiry = Instant.now().plus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    when(shopProvider.getShop("shop-1")).thenReturn(Optional.of(new ShopInfo("shop-1", "pro-id", expiry)));

    planService.grantForOrder(order("pro-id"));

    ArgumentCaptor<PlanTransaction> tx = ArgumentCaptor.forClass(PlanTransaction.class);
    verify(planTransactionRepository).insert(tx.capture());
    assertThat(tx.getValue().getTermStartsAt()).isEqualTo(expiry);
    verify(shopProvider).updatePlan("shop-1", "pro-id", tx.getValue().getTermEndsAt());
    assertThat(tx.getValue().getTermEndsAt()).isAfter(expiry.plus(360, ChronoUnit.DAYS));
  }

  @Test
  void aPurchaseAfterTheTrialStartsNowAndSyncsTheSubscriptionInTheSameTransaction() {
    when(shopProvider.getShop("shop-1")).thenReturn(Optional.of(new ShopInfo("shop-1", null, Instant.now().plusSeconds(60))));
    Instant before = Instant.now();

    planService.grantForOrder(order("pro-id"));

    ArgumentCaptor<PlanTransaction> tx = ArgumentCaptor.forClass(PlanTransaction.class);
    verify(planTransactionRepository).insert(tx.capture());
    assertThat(tx.getValue().getTermStartsAt()).isBetween(before, Instant.now());
    ArgumentCaptor<ShopInfo> synced = ArgumentCaptor.forClass(ShopInfo.class);
    verify(shopSubscriptionService).recordGrant(synced.capture(), eq("order-1"));
    assertThat(synced.getValue().planId()).isEqualTo("pro-id");
  }

  @Test
  void anOrderAlreadyGrantedIsNotGrantedAgain() {
    when(planTransactionRepository.existsByPaymentOrderId("order-1")).thenReturn(true);

    planService.grantForOrder(order("pro-id"));

    verify(planTransactionRepository, never()).insert(any(PlanTransaction.class));
    verify(shopProvider, never()).updatePlan(anyString(), anyString(), any());
  }

  @Test
  void aParallelGrantRejectedByTheUniqueIndexIsTreatedAsGranted() {
    doThrow(new DuplicateKeyException("paymentOrderId_unique"))
        .when(mongoTx).run(eq(MongoTx.PLAN_GRANT), anyString(), any(Runnable.class));

    planService.grantForOrder(order("pro-id"));
  }

  @Test
  void aDifferentPlanIsRefusedWhilePaidDaysRemain() {
    when(shopProvider.getShop("shop-1"))
        .thenReturn(Optional.of(new ShopInfo("shop-1", "pro-id", Instant.now().plus(10, ChronoUnit.DAYS))));

    assertThatThrownBy(() -> planService.requirePlanChangeAllowed("shop-1", starter))
        .isInstanceOf(BaseException.class)
        .extracting(e -> ((BaseException) e).getErrorCode())
        .isEqualTo(ErrorCode.PLAN_CHANGE_NOT_ALLOWED);
  }

  @Test
  void theSamePlanTheTrialOrAnEndedPlanMayBeBought() {
    when(shopProvider.getShop("renewing"))
        .thenReturn(Optional.of(new ShopInfo("renewing", "pro-id", Instant.now().plus(10, ChronoUnit.DAYS))));
    when(shopProvider.getShop("trial"))
        .thenReturn(Optional.of(new ShopInfo("trial", null, Instant.now().plus(2, ChronoUnit.DAYS))));
    when(shopProvider.getShop("ended"))
        .thenReturn(Optional.of(new ShopInfo("ended", "pro-id", Instant.now().minus(1, ChronoUnit.DAYS))));

    planService.requirePlanChangeAllowed("renewing", pro);
    planService.requirePlanChangeAllowed("trial", starter);
    planService.requirePlanChangeAllowed("ended", starter);
  }

  @Test
  void aLegacyCopyOfTheSamePlanCodeCountsAsARenewal() {
    Plan legacyPro = plan("legacy-pro", "PROFESSIONAL");
    when(shopProvider.getShop("shop-1"))
        .thenReturn(Optional.of(new ShopInfo("shop-1", "pro-id", Instant.now().plus(10, ChronoUnit.DAYS))));

    planService.requirePlanChangeAllowed("shop-1", legacyPro);
  }

  private static Plan plan(String id, String code) {
    Plan plan = new Plan();
    plan.setId(id);
    plan.setCode(code);
    plan.setPlanName(code);
    return plan;
  }

  private static PlanPaymentOrder order(String planId) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setPlanId(planId);
    order.setDurationMonths(12);
    return order;
  }
}
