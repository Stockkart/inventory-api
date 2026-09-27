package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.model.ShopAddOnSource;
import com.inventory.plan.domain.repository.ShopAddOnRepository;
import com.inventory.plan.mapper.AddOnMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class ShopAddOnServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final Instant TERM_END = Instant.parse("2027-09-01T10:00:00Z");

  @Mock private ShopAddOnRepository shopAddOnRepository;
  @Mock private AddOnCatalogueService catalogue;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private EntitlementService entitlementService;
  @Mock private AddOnMapper addOnMapper;

  @InjectMocks
  private ShopAddOnService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void grantsEachAddOnLineWithTheRightExpiryAndCredits() {
    when(catalogue.byCode(anyCollection())).thenReturn(Map.of(
        "MARKETING_MODULE", addOn("MARKETING_MODULE", AddOnBillingType.ANNUAL, AddOnGrantType.FEATURE, PlanFeature.MARKETING, 1),
        "ADDITIONAL_USER", addOn("ADDITIONAL_USER", AddOnBillingType.ANNUAL, AddOnGrantType.SEATS, null, 1),
        "OCR_TOPUP_500", addOn("OCR_TOPUP_500", AddOnBillingType.ONE_TIME, AddOnGrantType.OCR_CREDITS, null, 500)));

    service.grantForOrder(order(), TERM_END);

    ArgumentCaptor<ShopAddOn> grants = ArgumentCaptor.forClass(ShopAddOn.class);
    verify(mongoTemplate, times(3)).insert(grants.capture());
    assertThat(grants.getAllValues()).allSatisfy(grant -> {
      assertThat(grant.getSourceOrderId()).isEqualTo("order-1");
      assertThat(grant.getSource()).isEqualTo(ShopAddOnSource.ORDER);
    });
    ShopAddOn feature = grants.getAllValues().get(0);
    assertThat(feature.getGrantsFeature()).isEqualTo(PlanFeature.MARKETING);
    assertThat(feature.getExpiresAt()).isEqualTo(TERM_END);
    ShopAddOn seats = grants.getAllValues().get(1);
    assertThat(seats.getGrantedQuantity()).isEqualTo(3);
    assertThat(seats.getRemainingCredits()).isNull();
    ShopAddOn credits = grants.getAllValues().get(2);
    assertThat(credits.getGrantedQuantity()).isEqualTo(1000);
    assertThat(credits.getRemainingCredits()).isEqualTo(1000);
    assertThat(credits.getExpiresAt()).isNull();
    verify(entitlementService).invalidate("shop-1");
  }

  @Test
  void repeatedGrantForTheSameOrderIsANoOp() {
    when(catalogue.byCode(anyCollection())).thenReturn(Map.of(
        "MARKETING_MODULE", addOn("MARKETING_MODULE", AddOnBillingType.ANNUAL, AddOnGrantType.FEATURE, PlanFeature.MARKETING, 1),
        "ADDITIONAL_USER", addOn("ADDITIONAL_USER", AddOnBillingType.ANNUAL, AddOnGrantType.SEATS, null, 1),
        "OCR_TOPUP_500", addOn("OCR_TOPUP_500", AddOnBillingType.ONE_TIME, AddOnGrantType.OCR_CREDITS, null, 500)));
    when(mongoTemplate.insert(any(ShopAddOn.class))).thenThrow(new DuplicateKeyException("dup"));

    service.grantForOrder(order(), TERM_END);

    verify(mongoTemplate, times(3)).insert(any(ShopAddOn.class));
  }

  @Test
  void planOnlyOrderGrantsNothing() {
    PlanPaymentOrder order = order();
    order.setItems(List.of(line("PLAN", "PROFESSIONAL", 1)));

    service.grantForOrder(order, TERM_END);

    verifyNoInteractions(mongoTemplate, catalogue, entitlementService);
  }

  @Test
  void consumesOneCreditConditionally() {
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(ShopAddOn.class))).thenReturn(new ShopAddOn(), (ShopAddOn) null);

    assertThat(service.consumeOcrCredit("shop-1")).isTrue();
    assertThat(service.consumeOcrCredit("shop-1")).isFalse();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate, times(2)).findAndModify(query.capture(), any(Update.class),
        any(FindAndModifyOptions.class), eq(ShopAddOn.class));
    assertThat(query.getValue().getQueryObject().get("remainingCredits")).isEqualTo(new org.bson.Document("$gte", 1));
    assertThat(query.getValue().getQueryObject().get("grantType")).isEqualTo(AddOnGrantType.OCR_CREDITS);
    assertThat(query.getValue().getSortObject().get("purchasedAt")).isEqualTo(1);
  }

  private static PlanPaymentOrder order() {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setItems(List.of(
        line("PLAN", "PROFESSIONAL", 1),
        line("ADDON", "MARKETING_MODULE", 1),
        line("ADDON", "ADDITIONAL_USER", 3),
        line("OCR_TOPUP", "OCR_TOPUP_500", 2)));
    return order;
  }

  private static OrderLine line(String type, String code, int quantity) {
    return OrderLine.builder().type(type).code(code).quantity(quantity).build();
  }

  private static AddOn addOn(String code, AddOnBillingType billing, AddOnGrantType type, PlanFeature feature, int perUnit) {
    return AddOn.builder().code(code).name(code).billingType(billing).grantType(type)
        .grantsFeature(feature).grantsQuantity(perUnit).build();
  }
}
