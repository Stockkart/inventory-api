package com.inventory.pluginengine.pricing.defaultprovider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.pricing.PricingPolicyContext;
import com.inventory.pluginengine.pricing.PricingRateEntry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultRetailPricingPolicyTest {

  private DefaultRetailPricingPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new DefaultRetailPricingPolicy();
  }

  @Test
  void createSucceedsWithPriceToRetail() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder()
            .shopId("shop-1")
            .priceToRetail(new BigDecimal("100"))
            .costPrice(new BigDecimal("80"))
            .build();
    assertDoesNotThrow(() -> policy.validateCreate(ctx));
    PricingPolicyContext normalized = policy.normalizeOnCreate(ctx);
    assertEquals("priceToRetail", normalized.getDefaultRate());
    assertEquals(new BigDecimal("100"), normalized.getSellingPrice());
  }

  @Test
  void createFailsWithoutEffectivePrice() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder().shopId("shop-1").costPrice(new BigDecimal("80")).build();
    assertThrows(ValidationException.class, () -> policy.validateCreate(ctx));
  }

  @Test
  void createSucceedsWithRatesAndDefaultRate() {
    PricingPolicyContext ctx =
        PricingPolicyContext.builder()
            .shopId("shop-1")
            .rates(List.of(new PricingRateEntry("Rate-A", new BigDecimal("90"))))
            .defaultRate("Rate-A")
            .build();
    assertDoesNotThrow(() -> policy.validateCreate(ctx));
  }
}
