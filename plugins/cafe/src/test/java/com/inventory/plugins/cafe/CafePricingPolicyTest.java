package com.inventory.plugins.cafe;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.pricing.PricingPolicyContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CafePricingPolicyTest {

  private CafePricingPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new CafePricingPolicy();
  }

  @Test
  void createSucceedsWithCostOnly() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder()
            .shopId("shop-1")
            .costPrice(new BigDecimal("50"))
            .build();
    assertDoesNotThrow(() -> policy.validateCreate(ctx));
    PricingPolicyContext normalized = policy.normalizeOnCreate(ctx);
    assertNull(normalized.getPriceToRetail());
    assertNull(normalized.getDefaultRate());
  }

  @Test
  void createSucceedsWithCostAndSellingPrice() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder()
            .shopId("shop-1")
            .costPrice(new BigDecimal("40"))
            .sellingPrice(new BigDecimal("80"))
            .build();
    assertDoesNotThrow(() -> policy.validateCreate(ctx));
    assertEquals(new BigDecimal("80"), ctx.getSellingPrice());
  }

  @Test
  void createFailsWithoutCost() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder().shopId("shop-1").sellingPrice(new BigDecimal("80")).build();
    assertThrows(ValidationException.class, () -> policy.validateCreate(ctx));
  }

  @Test
  void createRejectsPtr() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder()
            .shopId("shop-1")
            .costPrice(new BigDecimal("40"))
            .priceToRetail(new BigDecimal("100"))
            .build();
    assertThrows(ValidationException.class, () -> policy.validateCreate(ctx));
  }
}
