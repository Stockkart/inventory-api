package com.inventory.plan.service.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.ReferralAttributionRepository;
import com.inventory.plan.domain.repository.ReferralRewardRepository;
import com.inventory.plan.mapper.ReferralAdminMapper;
import com.inventory.plan.mapper.ReferralAdminMapperImpl;
import com.inventory.plan.rest.dto.request.WalletAdjustmentRequest;
import com.inventory.plan.rest.dto.response.AdminReferralAttributionResponse;
import com.inventory.plan.rest.dto.response.AdminReferralRewardResponse;
import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.wallet.WalletService;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class ReferralAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @Mock private ReferralAttributionRepository attributionRepository;
  @Mock private ReferralRewardRepository rewardRepository;
  @Mock private PlanPaymentOrderRepository orderRepository;
  @Mock private ReferralRewardService rewardService;
  @Mock private WalletService walletService;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private ShopProvider shopProvider;
  @Mock private AuditService auditService;
  @Spy private ReferralAdminMapper mapper = new ReferralAdminMapperImpl();

  @InjectMocks
  private ReferralAdminService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    lenient().when(shopProvider.getReferralShop(anyString())).thenAnswer(inv -> Optional.of(
        new ShopProvider.ReferralShop(inv.getArgument(0), "Shop " + inv.getArgument(0), null, null, null)));
  }

  private static ReferralAttribution pending(String referrerShopId) {
    return ReferralAttribution.builder().id("a1").refereeShopId("referee").referrerShopId(referrerShopId)
        .rawReferredByName("Ravi").status(ReferralAttributionStatus.PENDING_REVIEW).build();
  }

  private static ReferralReward reward(ReferralRewardStatus status) {
    return ReferralReward.builder().id("r1").orderId("o1").referrerShopId("referrer").refereeShopId("referee")
        .rewardAmount(new BigDecimal("100.00")).status(status).build();
  }

  private void attributionUpdateReturns(ReferralAttribution result) {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralAttribution.class))).thenReturn(result);
  }

  @Test
  void approvingANameOnlyReferralNeedsAReferrer() {
    when(attributionRepository.findById("a1")).thenReturn(Optional.of(pending(null)));

    assertThatThrownBy(() -> service.approveAttribution("a1", null, "checked with owner", "admin"))
        .isInstanceOf(ValidationException.class);
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralAttribution.class));
  }

  @Test
  void aShopCannotBeApprovedAsItsOwnReferrer() {
    when(attributionRepository.findById("a1")).thenReturn(Optional.of(pending(null)));

    assertThatThrownBy(() -> service.approveAttribution("a1", "referee", "typo", "admin"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void approvalResolvesAndRewardsAnAlreadyFulfilledOrder() {
    ReferralAttribution resolved = pending("referrer");
    resolved.setStatus(ReferralAttributionStatus.RESOLVED);
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("o1");
    when(attributionRepository.findById("a1")).thenReturn(Optional.of(pending(null)));
    attributionUpdateReturns(resolved);
    when(orderRepository.findFirstByShopIdAndStatusOrderByFulfilledAtAsc("referee",
        PlanPaymentConstants.STATUS_FULFILLED)).thenReturn(Optional.of(order));

    AdminReferralAttributionResponse response = service.approveAttribution("a1", "referrer", "confirmed", "admin");

    assertThat(response.getStatus()).isEqualTo(ReferralAttributionStatus.RESOLVED);
    assertThat(response.getReferrerShopName()).isEqualTo("Shop referrer");
    verify(rewardService).recordForOrder(order);
    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("REFERRAL_APPROVED");
    assertThat(audit.getValue().getActorUserId()).isEqualTo("admin");
  }

  @Test
  void anAlreadyResolvedReferralCannotBeRejected() {
    ReferralAttribution resolved = pending("referrer");
    resolved.setStatus(ReferralAttributionStatus.RESOLVED);
    when(attributionRepository.findById("a1")).thenReturn(Optional.of(resolved));
    attributionUpdateReturns(null);

    assertThatThrownBy(() -> service.rejectAttribution("a1", "spam", "admin"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("RESOLVED");
    verify(auditService, never()).record(any());
  }

  @Test
  void everyActionNeedsAReason() {
    assertThatThrownBy(() -> service.rejectAttribution("a1", " ", "admin")).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.voidReward("r1", null, "admin")).isInstanceOf(ValidationException.class);
    verify(attributionRepository, never()).findById(any());
  }

  @Test
  void voidingOnlyTouchesUncreditedRewards() {
    ReferralReward voided = reward(ReferralRewardStatus.VOID);
    when(rewardRepository.findById("r1")).thenReturn(Optional.of(reward(ReferralRewardStatus.APPROVED)));
    when(rewardService.voidReward(eq("r1"), eq(Set.of(ReferralRewardStatus.PENDING, ReferralRewardStatus.APPROVED)),
        eq("ADMIN: duplicate shop"))).thenReturn(Optional.of(voided));

    AdminReferralRewardResponse response = service.voidReward("r1", "duplicate shop", "admin");

    assertThat(response.getStatus()).isEqualTo(ReferralRewardStatus.VOID);
    verify(auditService).record(any(AuditEntry.class));
  }

  @Test
  void clawbackIsOnlyForCreditedRewards() {
    when(rewardRepository.findById("r1")).thenReturn(Optional.of(reward(ReferralRewardStatus.PENDING)));

    assertThatThrownBy(() -> service.clawBackReward("r1", "fraud", "admin")).isInstanceOf(ValidationException.class);
    verify(rewardService, never()).reverseForOrder(any(), any(), any());
  }

  @Test
  void clawbackReversesThroughTheRewardService() {
    when(rewardRepository.findById("r1")).thenReturn(Optional.of(reward(ReferralRewardStatus.CREDITED)));
    when(rewardService.reverseForOrder("o1", "ADMIN: fraud", "admin"))
        .thenReturn(Optional.of(reward(ReferralRewardStatus.CLAWED_BACK)));

    assertThat(service.clawBackReward("r1", "fraud", "admin").getStatus()).isEqualTo(ReferralRewardStatus.CLAWED_BACK);
  }

  @Test
  void adjustmentMustBeNonZero() {
    assertThatThrownBy(() -> service.adjustWallet("s1", new WalletAdjustmentRequest(BigDecimal.ZERO, "fix", null), "admin"))
        .isInstanceOf(ValidationException.class);
    verify(walletService, never()).adjust(any(), any(), any(), any(), any());
  }

  @Test
  void debitBeyondAvailableIsRejected() {
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopProvider.ShopInfo("s1", null, null)));
    when(walletService.available("s1")).thenReturn(new BigDecimal("50.00"));
    when(walletService.adjust(eq("s1"), eq(new BigDecimal("-80")), eq("adj-1"), eq("correction"), eq("admin")))
        .thenReturn(WalletService.Outcome.REJECTED);

    assertThatThrownBy(() -> service.adjustWallet("s1",
        new WalletAdjustmentRequest(new BigDecimal("-80"), "correction", "adj-1"), "admin"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("50.00");
    verify(auditService, never()).record(any());
  }

  @Test
  void appliedAdjustmentIsAuditedButARepeatIsNot() {
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopProvider.ShopInfo("s1", null, null)));
    when(walletService.available("s1")).thenReturn(BigDecimal.ZERO);
    when(walletService.adjust(eq("s1"), any(), eq("adj-1"), eq("goodwill"), eq("admin")))
        .thenReturn(WalletService.Outcome.APPLIED, WalletService.Outcome.ALREADY_APPLIED);
    WalletAdjustmentRequest request = new WalletAdjustmentRequest(new BigDecimal("25"), "goodwill", "adj-1");

    service.adjustWallet("s1", request, "admin");
    service.adjustWallet("s1", request, "admin");

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("WALLET_ADJUSTED");
  }
}
