package com.inventory.product.service.vertical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.pluginengine.pricing.PricingPolicyContext;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.service.VerticalPricingPolicies;
import com.inventory.product.service.api.VerticalPricingPolicyPortImpl;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every save rebuilds rates through the plugin engine, so no rate field may be dropped there. */
class VerticalPricingPolicyPortImplRateFieldsTest {

  private static final VerticalPricingPolicy PASS_THROUGH = new VerticalPricingPolicy() {
    @Override public void validateCreate(PricingPolicyContext context) {}
    @Override public void validateUpdate(PricingPolicyContext existing, PricingPolicyContext patch) {}
    @Override public PricingPolicyContext normalizeOnCreate(PricingPolicyContext context) {
      return context;
    }
    @Override public PricingPolicyContext normalizeOnUpdate(
        PricingPolicyContext existing, PricingPolicyContext patch) {
      return patch;
    }
  };

  // pricing's side of the port and product's implementation together: the full round trip
  private final VerticalPricingPolicies port = new VerticalPricingPolicies(
      new VerticalPricingPolicyPortImpl(new PricingPolicyResolver(null, null) {
        @Override public VerticalPricingPolicy resolve(String verticalId) {
          return PASS_THROUGH;
        }
      }));

  @Test
  void everyRateFieldSurvivesPolicyRoundTrip() {
    Rate full = Rate.builder()
        .name("Rate-A")
        .price(new BigDecimal("84.18"))
        .base("priceToRetail")
        .baseValue(new BigDecimal("100.00"))
        .discountPercent(new BigDecimal("8"))
        .scheme(new Scheme("FIXED_UNITS", 10, 1, null))
        .manual(Boolean.TRUE)
        .gstMode("EXCLUSIVE")
        .build();
    CreatePricingRequest request = new CreatePricingRequest();
    request.setRates(List.of(full));

    port.validateAndNormalizeCreate(request);

    assertEquals(List.of(full), request.getRates());
  }

  @Test
  void everyRateFieldSurvivesTheEntityPath() {
    Rate full = Rate.builder()
        .name("Rate-B").price(new BigDecimal("75.00")).base("maximumRetailPrice")
        .baseValue(new BigDecimal("100.00")).discountPercent(new BigDecimal("25"))
        .scheme(new Scheme("PERCENTAGE", null, null, new BigDecimal("10")))
        .manual(Boolean.FALSE).gstMode("INCLUSIVE").build();
    Pricing pricing = new Pricing();
    pricing.setRates(List.of(full));

    port.normalizeEntity(pricing, null);

    assertEquals(List.of(full), pricing.getRates());
  }

  @Test
  void legacyRateWithOnlyNameAndPriceStaysLegacy() {
    CreatePricingRequest request = new CreatePricingRequest();
    request.setRates(List.of(new Rate("Rate-A", new BigDecimal("90"))));

    port.validateAndNormalizeCreate(request);

    Rate out = request.getRates().get(0);
    assertEquals("Rate-A", out.getName());
    assertEquals(new BigDecimal("90"), out.getPrice());
    assertNull(out.getBase());
    assertNull(out.getBaseValue());
    assertNull(out.getDiscountPercent());
    assertNull(out.getScheme());
    assertNull(out.getManual());
    assertNull(out.getGstMode());
  }
}
