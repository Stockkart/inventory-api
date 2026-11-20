package com.inventory.plan.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PaidTerm;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.service.AddOnCatalogueService;
import com.inventory.plan.service.EntitlementService;
import com.inventory.plan.service.PlanService;
import com.inventory.plan.service.ShopAddOnService;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Fulfilment of an add-on-only order: no plan grant, annual add-ons end with the shop's current
 * term, OCR credits never expire, and a lapsed plan fails the step rather than granting.
 */
@ExtendWith(MockitoExtension.class)
class AddOnOnlyFulfilmentTest {

  private static final Instant TERM_END = Instant.parse("2027-03-01T00:00:00Z");

  @Mock private PlanService planService;
  @Mock private PlanTransactionRepository planTransactionRepository;
  @Mock private EntitlementService entitlementService;
  @Mock private AddOnCatalogueService catalogue;
  @Mock private MongoTemplate mongoTemplate;

  @InjectMocks private ShopAddOnService shopAddOnService;
  @InjectMocks private PlanGrantStep planGrantStep;
  @InjectMocks private AddOnGrantStep addOnGrantStep;

  @BeforeEach
  void wire() {
    ReflectionTestUtils.setField(addOnGrantStep, "shopAddOnService", shopAddOnService);
  }

  @Test
  void grantsNoPlan() {
    planGrantStep.apply(addOnOnlyOrder());

    verify(planService, never()).grantForOrder(any());
  }

  @Test
  void planOrdersStillGrantTheirPlan() {
    PlanPaymentOrder order = addOnOnlyOrder();
    order.setAddOnOnly(false);

    planGrantStep.apply(order);

    verify(planService).grantForOrder(order);
  }

  @Test
  void annualAddOnsEndWithTheCurrentTermAndOcrCreditsNeverExpire() {
    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.of(new PaidTerm("plan-pro", TERM_END)));
    when(catalogue.byCode(anyCollection())).thenReturn(Map.of(
        "MARKETING_MODULE", addOn("MARKETING_MODULE", AddOnBillingType.ANNUAL, AddOnGrantType.FEATURE, 1),
        "OCR_TOPUP_500", addOn("OCR_TOPUP_500", AddOnBillingType.ONE_TIME, AddOnGrantType.OCR_CREDITS, 500)));

    addOnGrantStep.apply(addOnOnlyOrder());

    ArgumentCaptor<ShopAddOn> grants = ArgumentCaptor.forClass(ShopAddOn.class);
    verify(mongoTemplate, times(2)).insert(grants.capture());
    ShopAddOn marketing = grants.getAllValues().get(0);
    assertThat(marketing.getAddOnCode()).isEqualTo("MARKETING_MODULE");
    assertThat(marketing.getExpiresAt()).isEqualTo(TERM_END);
    ShopAddOn credits = grants.getAllValues().get(1);
    assertThat(credits.getExpiresAt()).isNull();
    assertThat(credits.getRemainingCredits()).isEqualTo(500);
    verify(planTransactionRepository, never()).findFirstByPaymentOrderId(any());
  }

  @Test
  void aLapsedPlanFailsTheStepInsteadOfGranting() {
    when(entitlementService.paidTerm("shop-1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> addOnGrantStep.apply(addOnOnlyOrder()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no longer on an active paid plan");
    verify(mongoTemplate, never()).insert(any(ShopAddOn.class));
  }

  @Test
  void planOrdersStillEndAddOnsWithTheTermTheyBought() {
    PlanPaymentOrder order = addOnOnlyOrder();
    order.setAddOnOnly(false);
    PlanTransaction tx = new PlanTransaction();
    tx.setTermEndsAt(TERM_END.plusSeconds(60));
    when(planTransactionRepository.findFirstByPaymentOrderId("order-1")).thenReturn(Optional.of(tx));
    when(catalogue.byCode(anyCollection())).thenReturn(Map.of(
        "MARKETING_MODULE", addOn("MARKETING_MODULE", AddOnBillingType.ANNUAL, AddOnGrantType.FEATURE, 1),
        "OCR_TOPUP_500", addOn("OCR_TOPUP_500", AddOnBillingType.ONE_TIME, AddOnGrantType.OCR_CREDITS, 500)));

    addOnGrantStep.apply(order);

    ArgumentCaptor<ShopAddOn> grants = ArgumentCaptor.forClass(ShopAddOn.class);
    verify(mongoTemplate, times(2)).insert(grants.capture());
    assertThat(grants.getAllValues().get(0).getExpiresAt()).isEqualTo(TERM_END.plusSeconds(60));
    verify(entitlementService, never()).paidTerm(any());
  }

  private static PlanPaymentOrder addOnOnlyOrder() {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setPlanId("plan-pro");
    order.setAddOnOnly(true);
    order.setItems(List.of(
        line(PricingConstants.ITEM_TYPE_ADDON, "MARKETING_MODULE", "1999"),
        line(PricingConstants.ITEM_TYPE_OCR_TOPUP, "OCR_TOPUP_500", "199")));
    return order;
  }

  private static OrderLine line(String type, String code, String price) {
    return OrderLine.builder().type(type).code(code).quantity(1).unitPrice(new BigDecimal(price))
        .discount(BigDecimal.ZERO).lineTotal(new BigDecimal(price)).build();
  }

  private static AddOn addOn(String code, AddOnBillingType billing, AddOnGrantType type, int grants) {
    return AddOn.builder().code(code).name(code).price(BigDecimal.TEN).billingType(billing).grantType(type)
        .grantsFeature(type == AddOnGrantType.FEATURE ? PlanFeature.MARKETING : null)
        .grantsQuantity(grants).active(true).build();
  }
}
