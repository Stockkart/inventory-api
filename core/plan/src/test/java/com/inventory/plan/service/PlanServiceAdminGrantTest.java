package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.api.port.ShopPort;
import com.inventory.plan.api.port.ShopPort.ShopInfo;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.utils.constants.PlanConstants;
import com.inventory.plan.validation.PlanAdminValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** Admin plan grants keep remaining days on the same plan and never replace another paid plan. */
@ExtendWith(MockitoExtension.class)
class PlanServiceAdminGrantTest {

  @Mock private ShopPort shopProvider;
  @Mock private PlanTransactionRepository planTransactionRepository;
  @Mock private ShopSubscriptionService shopSubscriptionService;
  @Mock private EntitlementService entitlementService;
  @Mock private MetricsWrapper metrics;
  @Mock private com.inventory.common.tx.MongoTx mongoTx;
  @Mock private com.inventory.plan.domain.repository.PlanRepository planRepository;
  @Spy private PlanAdminValidator planAdminValidator = new PlanAdminValidator();
  @Spy private PlanTransactionMapper planTransactionMapper = Mockito.mock(PlanTransactionMapper.class,
      Mockito.CALLS_REAL_METHODS);

  @InjectMocks
  private PlanService planService;

  private Plan pro;

  @BeforeEach
  void setUp() {
    pro = new Plan();
    pro.setId("pro");
    pro.setCode("PROFESSIONAL");
    pro.setPlanName("Professional");
    org.mockito.Mockito.lenient().doAnswer(inv -> {
      ((Runnable) inv.getArgument(2)).run();
      return null;
    }).when(mongoTx).run(org.mockito.ArgumentMatchers.eq(com.inventory.common.tx.MongoTx.PLAN_GRANT),
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Runnable.class));
  }

  @Test
  void theSamePlanExtendsFromItsCurrentExpiry() {
    Instant expiry = Instant.now().plus(Duration.ofDays(10));
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopInfo("s1", "pro", expiry)));

    PlanService.GrantResult result = planService.grantByAdmin("s1", pro, 1);

    assertThat(result.expiresAtAfter()).isAfter(expiry.plus(Duration.ofDays(27)));
    verify(shopProvider).updatePlan("s1", "pro", result.expiresAtAfter());
    // the grant keeps the granted plan's terms on the subscription, like a paid one
    verify(shopSubscriptionService).recordGrant(new ShopInfo("s1", "pro", result.expiresAtAfter()), null);
    ArgumentCaptor<PlanTransaction> tx = ArgumentCaptor.forClass(PlanTransaction.class);
    verify(planTransactionRepository).save(tx.capture());
    assertThat(tx.getValue().getPaymentMethod()).isEqualTo(PlanConstants.ADMIN_GRANT_PAYMENT_METHOD);
    assertThat(tx.getValue().getPaymentOrderId()).isNull();
    assertThat(tx.getValue().getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(tx.getValue().getTermStartsAt()).isEqualTo(expiry);
    verify(entitlementService).invalidate("s1");
  }

  @Test
  void aTrialShopStartsTheGrantedPlanNow() {
    Instant trialEnd = Instant.now().plus(Duration.ofDays(2));
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopInfo("s1", null, trialEnd)));

    PlanService.GrantResult result = planService.grantByAdmin("s1", pro, 3);

    // three months from now, not from the trial's end
    assertThat(result.expiresAtAfter()).isBefore(Instant.now().plus(Duration.ofDays(93)))
        .isAfter(Instant.now().plus(Duration.ofDays(88)));
    assertThat(result.planIdBefore()).isNull();
  }

  @Test
  void anotherPlanWithPaidDaysLeftIsRejected() {
    when(shopProvider.getShop("s1"))
        .thenReturn(Optional.of(new ShopInfo("s1", "starter", Instant.now().plus(Duration.ofDays(5)))));

    assertThatThrownBy(() -> planService.grantByAdmin("s1", pro, 1)).isInstanceOf(ValidationException.class);
    verify(shopProvider, never()).updatePlan(anyString(), anyString(), any());
  }

  @Test
  void anotherPlanAfterTheOldOneEndedStartsNow() {
    when(shopProvider.getShop("s1"))
        .thenReturn(Optional.of(new ShopInfo("s1", "starter", Instant.now().minus(Duration.ofDays(1)))));

    planService.grantByAdmin("s1", pro, 1);

    verify(shopProvider).updatePlan(eq("s1"), eq("pro"), any());
  }
}
