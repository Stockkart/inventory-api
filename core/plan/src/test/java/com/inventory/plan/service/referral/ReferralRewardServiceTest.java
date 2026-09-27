package com.inventory.plan.service.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.domain.model.ShopCreditSource;
import com.inventory.plan.domain.repository.ReferralRewardRepository;
import com.inventory.plan.mapper.ReferralRewardMapper;
import com.inventory.plan.mapper.ReferralRewardMapperImpl;
import com.inventory.plan.rest.dto.response.ReferralRewardsResponse;
import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.ShopProvider.ReferralShop;
import com.inventory.plan.service.wallet.WalletService;
import com.inventory.plan.utils.constants.PricingConstants;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class ReferralRewardServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Mock private ReferralRewardRepository rewardRepository;
  @Mock private ReferralAttributionService attributionService;
  @Mock private WalletService walletService;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private ShopProvider shopProvider;
  @Spy private ReferralRewardMapper rewardMapper = new ReferralRewardMapperImpl();

  @InjectMocks
  private ReferralRewardService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    lenient().when(rewardRepository.insert(any(ReferralReward.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(attributionService.resolvedReferrer("referee")).thenReturn(Optional.of("referrer"));
  }

  private static OrderLine line(String type, String lineTotal, String walletCredit) {
    return OrderLine.builder().type(type).lineTotal(new BigDecimal(lineTotal))
        .walletCredit(walletCredit == null ? null : new BigDecimal(walletCredit)).build();
  }

  private static PlanPaymentOrder order(OrderLine... lines) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("referee");
    order.setPlanCode("PROFESSIONAL");
    order.setItems(List.of(lines));
    return order;
  }

  @Test
  void aReferredPurchaseRecordsAHeldRewardOnTheNetPlanLine() {
    ReferralReward reward = service.recordForOrder(order(
        line(PricingConstants.ITEM_TYPE_PLAN, "9999.00", "999.00"),
        line(PricingConstants.ITEM_TYPE_ADDON, "2000.00", null))).orElseThrow();

    assertThat(reward.getStatus()).isEqualTo(ReferralRewardStatus.PENDING);
    assertThat(reward.getReferrerShopId()).isEqualTo("referrer");
    assertThat(reward.getBasePlanAmount()).isEqualByComparingTo("9000.00");
    assertThat(reward.getRewardAmount()).isEqualByComparingTo("900.00");
    assertThat(reward.getHoldUntil()).isEqualTo(NOW.plus(Duration.ofDays(15)));
  }

  @Test
  void theConfiguredPercentIsCappedAtTen() {
    service.configuredPercent = new BigDecimal("25");

    ReferralReward reward = service.recordForOrder(order(line(PricingConstants.ITEM_TYPE_PLAN, "1000", null)))
        .orElseThrow();

    assertThat(reward.getRewardPercent()).isEqualByComparingTo("10");
    assertThat(reward.getRewardAmount()).isEqualByComparingTo("100.00");
  }

  @Test
  void noReferrerOrNothingPaidForThePlanMeansNoReward() {
    when(attributionService.resolvedReferrer("referee")).thenReturn(Optional.empty());
    assertThat(service.recordForOrder(order(line(PricingConstants.ITEM_TYPE_PLAN, "1000", null)))).isEmpty();

    when(attributionService.resolvedReferrer("referee")).thenReturn(Optional.of("referrer"));
    assertThat(service.recordForOrder(order(line(PricingConstants.ITEM_TYPE_PLAN, "1000", "1000")))).isEmpty();
    assertThat(service.recordForOrder(order(line(PricingConstants.ITEM_TYPE_ADDON, "500", null)))).isEmpty();
    verify(rewardRepository, never()).insert(any(ReferralReward.class));
  }

  @Test
  void aReferrerOverTheCapGetsAVoidReward() {
    when(rewardRepository.countByReferrerShopIdAndStatusInAndCreatedAtAfter(eq("referrer"), anyCollection(), any()))
        .thenReturn(20L);

    ReferralReward reward = service.recordForOrder(order(line(PricingConstants.ITEM_TYPE_PLAN, "1000", null)))
        .orElseThrow();

    assertThat(reward.getStatus()).isEqualTo(ReferralRewardStatus.VOID);
    assertThat(reward.getVoidReason()).isEqualTo(ReferralRewardService.VOID_CAP_REACHED);
  }

  @Test
  void aSecondRecordForTheSameOrderReturnsTheFirst() {
    ReferralReward existing = ReferralReward.builder().id("r1").orderId("order-1").build();
    when(rewardRepository.insert(any(ReferralReward.class))).thenThrow(new DuplicateKeyException("dup"));
    when(rewardRepository.findByOrderId("order-1")).thenReturn(Optional.of(existing));

    assertThat(service.recordForOrder(order(line(PricingConstants.ITEM_TYPE_PLAN, "1000", null))))
        .contains(existing);
  }

  @Test
  void releaseClaimsCreditsAndMarksDueRewards() {
    ReferralReward due = ReferralReward.builder().id("r1").referrerShopId("referrer").orderId("order-1")
        .rewardAmount(new BigDecimal("900.00")).status(ReferralRewardStatus.PENDING).build();
    ReferralReward claimed = due.toBuilder().status(ReferralRewardStatus.CREDITING).build();
    when(rewardRepository.findByStatusInAndHoldUntilLessThanEqual(anyCollection(), eq(NOW), any(Pageable.class)))
        .thenReturn(List.of(due));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralReward.class))).thenReturn(claimed);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ReferralReward.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));

    assertThat(service.releaseDue(50)).isEqualTo(1);
    verify(walletService).credit(eq("referrer"), eq(new BigDecimal("900.00")), eq(ShopCreditSource.REFERRAL_REWARD),
        eq("r1"), anyString(), isNull());
  }

  @Test
  void aRewardClaimedElsewhereIsNotCredited() {
    ReferralReward due = ReferralReward.builder().id("r1").status(ReferralRewardStatus.PENDING).build();
    when(rewardRepository.findByStatusInAndHoldUntilLessThanEqual(anyCollection(), eq(NOW), any(Pageable.class)))
        .thenReturn(List.of(due));

    assertThat(service.releaseDue(50)).isZero();
    verify(walletService, never()).credit(any(), any(), any(), any(), any(), any());
  }

  @Test
  void aFailedCreditLeavesTheRewardCreditingForTheNextRun() {
    ReferralReward stuck = ReferralReward.builder().id("r1").referrerShopId("referrer")
        .rewardAmount(BigDecimal.TEN).status(ReferralRewardStatus.CREDITING).build();
    when(rewardRepository.findByStatusAndUpdatedAtBefore(eq(ReferralRewardStatus.CREDITING), any(), any(Pageable.class)))
        .thenReturn(List.of(stuck));
    when(walletService.credit(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("down"));

    assertThat(service.releaseDue(50)).isZero();
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class), eq(ReferralReward.class));
  }

  private static ReferralReward reward(ReferralRewardStatus status) {
    return ReferralReward.builder().id("r1").orderId("order-1").referrerShopId("referrer")
        .rewardAmount(new BigDecimal("900.00")).status(status).build();
  }

  @Test
  void aRefundVoidsARewardNotYetCredited() {
    ReferralReward voided = reward(ReferralRewardStatus.VOID);
    when(rewardRepository.findByOrderId("order-1")).thenReturn(Optional.of(reward(ReferralRewardStatus.PENDING)));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralReward.class))).thenReturn(voided);

    assertThat(service.reverseForOrder("order-1", "ORDER_REFUNDED", null)).contains(voided);
    verify(walletService, never()).clawback(any(), any(), any(), any(), any());
  }

  @Test
  void aRefundClawsBackACreditedReward() {
    ReferralReward clawedBack = reward(ReferralRewardStatus.CLAWED_BACK);
    when(rewardRepository.findByOrderId("order-1")).thenReturn(Optional.of(reward(ReferralRewardStatus.CREDITED)));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralReward.class))).thenReturn(clawedBack);

    assertThat(service.reverseForOrder("order-1", "ORDER_REFUNDED", "admin-1")).contains(clawedBack);
    verify(walletService).clawback(eq("referrer"), eq(new BigDecimal("900.00")), eq("r1"), anyString(), eq("admin-1"));
  }

  @Test
  void aRewardBeingCreditedIsFinishedThenClawedBack() {
    ReferralReward clawedBack = reward(ReferralRewardStatus.CLAWED_BACK);
    when(rewardRepository.findByOrderId("order-1")).thenReturn(
        Optional.of(reward(ReferralRewardStatus.CREDITING)), Optional.of(reward(ReferralRewardStatus.CREDITED)));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(ReferralReward.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralReward.class))).thenReturn(clawedBack);

    assertThat(service.reverseForOrder("order-1", "ORDER_REFUNDED", null)).contains(clawedBack);
    verify(walletService).credit(eq("referrer"), any(), eq(ShopCreditSource.REFERRAL_REWARD), eq("r1"), anyString(), isNull());
    verify(walletService).clawback(eq("referrer"), any(), eq("r1"), anyString(), isNull());
  }

  @Test
  void aVoidedRewardNeedsNothingMore() {
    when(rewardRepository.findByOrderId("order-1")).thenReturn(Optional.of(reward(ReferralRewardStatus.VOID)));

    assertThat(service.reverseForOrder("order-1", "ORDER_REFUNDED", null)).isPresent();
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ReferralReward.class));
  }

  @Test
  void theReferrerSeesPendingAndCreditedTotals() {
    when(rewardRepository.findByReferrerShopIdOrderByCreatedAtDesc(eq("referrer"), any(Pageable.class))).thenReturn(List.of(
        ReferralReward.builder().refereeShopId("a").rewardAmount(new BigDecimal("100")).status(ReferralRewardStatus.PENDING).build(),
        ReferralReward.builder().refereeShopId("b").rewardAmount(new BigDecimal("50")).status(ReferralRewardStatus.CREDITED).build(),
        ReferralReward.builder().refereeShopId("c").rewardAmount(new BigDecimal("70")).status(ReferralRewardStatus.VOID).build()));
    when(shopProvider.getReferralShop(anyString()))
        .thenAnswer(inv -> Optional.of(new ReferralShop(inv.getArgument(0), "Shop " + inv.getArgument(0), null, null, null)));

    ReferralRewardsResponse response = service.listForReferrer("referrer");

    assertThat(response.getPendingAmount()).isEqualByComparingTo("100");
    assertThat(response.getCreditedAmount()).isEqualByComparingTo("50");
    assertThat(response.getRewards()).extracting("refereeShopName").containsExactly("Shop a", "Shop b", "Shop c");
  }
}
