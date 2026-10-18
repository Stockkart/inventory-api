package com.inventory.pricing.service;

import static com.inventory.pricing.service.RateGenerationTestFixtures.CASHIER;
import static com.inventory.pricing.service.RateGenerationTestFixtures.OWNER;
import static com.inventory.pricing.service.RateGenerationTestFixtures.SHOP;
import static com.inventory.pricing.service.RateGenerationTestFixtures.dec;
import static com.inventory.pricing.service.RateGenerationTestFixtures.find;
import static com.inventory.pricing.service.RateGenerationTestFixtures.fresh;
import static com.inventory.pricing.service.RateGenerationTestFixtures.namePrice;
import static com.inventory.pricing.service.RateGenerationTestFixtures.rateA;
import static com.inventory.pricing.service.RateGenerationTestFixtures.rateM;
import static com.inventory.pricing.service.RateGenerationTestFixtures.stored;
import static com.inventory.pricing.service.RateGenerationTestFixtures.storedOverrideFivePercent;
import static com.inventory.pricing.service.RateGenerationTestFixtures.storedRateA100;
import static com.inventory.pricing.service.RateGenerationTestFixtures.withDisc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.SkippedRateRule;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.utils.constants.PricingMetricsConstants;
import com.inventory.pricing.validation.PricingValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateGenerationServiceTest {

  @Mock private ShopRateRulesPort port;
  @Mock private MetricsWrapper metrics;
  @Spy private PricingValidator pricingValidator = new PricingValidator();
  @InjectMocks private RateGenerationService service;

  @BeforeEach
  void setUp() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(rateA()));
    when(port.canEditRates(OWNER, SHOP)).thenReturn(true);
    when(port.canEditRates(CASHIER, SHOP)).thenReturn(false);
  }

  @Test
  void createGeneratesRatesFromRules() {
    Pricing p = fresh("100", "150", null);

    List<SkippedRateRule> warnings = service.apply(p, null, null, null);

    assertTrue(warnings.isEmpty());
    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertEquals(dec("8"), a.getDiscountPercent());
    assertFalse(a.getManual());
  }

  @Test
  void createReplacesIncomingRuleRatesWithGeneratedOnes() {
    Pricing p = fresh("100", "150", null);

    service.apply(p, List.of(withDisc("Rate-A", "999", "1")), null, CASHIER);

    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertEquals(dec("8"), a.getDiscountPercent());
    assertFalse(a.getManual());
  }

  @Test
  void createByUserWithoutGrantSucceeds() {
    Pricing p = fresh("100", "150", null);

    service.apply(p, List.of(withDisc("Rate-A", "1", "50")), List.of("Rate-A"), CASHIER);

    verify(port, never()).canEditRates(anyString(), anyString());
    assertEquals(dec("83.64"), find(p.getRates(), "Rate-A").getPrice());
  }

  @Test
  void noRulesLeavesPricingUntouched() {
    when(port.rulesFor(SHOP)).thenReturn(List.of());
    Pricing p = stored("p1", "100", "150", null, namePrice("Rate-X", "77"));
    List<Rate> before = p.getRates();

    List<SkippedRateRule> warnings = service.apply(p, List.of(namePrice("Rate-Y", "50")), null, CASHIER);

    assertTrue(warnings.isEmpty());
    assertSame(before, p.getRates());
    assertNull(p.getRates().get(0).getManual());
    assertEquals(dec("100"), p.getSellingPrice());
  }

  @Test
  void noPortLeavesPricingUntouched() {
    ReflectionTestUtils.setField(service, "shopRateRulesPort", null);
    Pricing p = stored("p1", "100", "150", null, namePrice("Rate-X", "77"));
    List<Rate> before = p.getRates();

    List<SkippedRateRule> warnings = service.apply(p, null, null, CASHIER);

    assertTrue(warnings.isEmpty());
    assertSame(before, p.getRates());
  }

  @Test
  void overrideEightToFivePercentRecalculatesThatBatchOnly() {
    Pricing b1 = stored("b1", "100", "150", null, storedRateA100());
    Pricing b2 = fresh("110", "150", null);

    service.apply(b1, List.of(withDisc("Rate-A", "83.64", "5")), null, OWNER);
    service.apply(b2, null, null, null);

    Rate b1a = find(b1.getRates(), "Rate-A");
    assertEquals(dec("86.36"), b1a.getPrice());
    assertEquals(dec("5"), b1a.getDiscountPercent());
    assertTrue(b1a.getManual());
    Rate b2a = find(b2.getRates(), "Rate-A");
    assertEquals(dec("92.00"), b2a.getPrice());
    assertEquals(dec("8"), b2a.getDiscountPercent());
    assertFalse(b2a.getManual());
  }

  @Test
  void overriddenBatchPtrEditUsesItsOwnTerms() {
    Pricing b1 = stored("b1", "105", "150", null, storedOverrideFivePercent());

    service.apply(b1, null, null, CASHIER);

    Rate a = find(b1.getRates(), "Rate-A");
    assertEquals(dec("90.68"), a.getPrice());
    assertEquals(dec("5"), a.getDiscountPercent());
    assertEquals(dec("105"), a.getBaseValue());
    assertTrue(a.getManual());
  }

  @Test
  void priceSentForRuleRateIsIgnored() {
    Pricing p = stored("p1", "100", "150", null, storedRateA100());

    service.apply(p, List.of(namePrice("Rate-A", "500")), null, OWNER);

    assertEquals(dec("83.64"), find(p.getRates(), "Rate-A").getPrice());
  }

  @Test
  void nameAndPriceOnlyRateIsNotAnOverride() {
    Pricing byCashier = stored("p1", "100", "150", null, storedRateA100());
    Pricing byOwner = stored("p2", "100", "150", null, storedRateA100());

    service.apply(byCashier, List.of(namePrice("Rate-A", "83.64")), null, CASHIER);
    service.apply(byOwner, List.of(namePrice("Rate-A", "83.64")), null, OWNER);

    for (Pricing p : List.of(byCashier, byOwner)) {
      Rate a = find(p.getRates(), "Rate-A");
      assertEquals(dec("8"), a.getDiscountPercent());
      assertEquals(RateGenerationTestFixtures.tenPlusOne(), a.getScheme());
      assertFalse(a.getManual());
    }
  }

  @Test
  void overrideWithoutGrantIsForbidden() {
    Pricing p = stored("p1", "100", "150", null, storedRateA100());

    BaseException e = assertThrows(BaseException.class,
        () -> service.apply(p, List.of(withDisc("Rate-A", "83.64", "5")), null, CASHIER));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    assertEquals("You do not have permission to edit rates", e.getMessage());
  }

  @Test
  void resetWithoutGrantIsForbidden() {
    Pricing p = stored("p1", "100", "150", null, storedOverrideFivePercent());

    BaseException e = assertThrows(BaseException.class,
        () -> service.apply(p, null, List.of("Rate-A"), CASHIER));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
  }

  @Test
  void overrideWithDiscOutsideRangeIsRejected() {
    Pricing p = stored("p1", "100", "150", null, storedRateA100());

    assertThrows(ValidationException.class,
        () -> service.apply(p, List.of(withDisc("Rate-A", "83.64", "101")), null, OWNER));
  }

  @Test
  void belowCostOverrideKeptWithWarning() {
    Pricing p = stored("p1", "100", "150", "90", storedRateA100());

    List<SkippedRateRule> warnings =
        service.apply(p, List.of(withDisc("Rate-A", "83.64", "5")), null, OWNER);

    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("86.36"), a.getPrice());
    assertTrue(a.getManual());
    assertEquals(List.of(new SkippedRateRule("Rate-A", "BELOW_COST")), warnings);
  }

  @Test
  void belowCostRuleRateIsRemovedAndCounted() {
    Pricing p = stored("p1", "100", "150", "90", storedRateA100());

    List<SkippedRateRule> warnings = service.apply(p, null, null, CASHIER);

    assertNull(find(p.getRates(), "Rate-A"));
    assertEquals(List.of(new SkippedRateRule("Rate-A", "BELOW_COST")), warnings);
    verify(metrics).increment(PricingMetricsConstants.RATE_RULE_SKIPPED_TOTAL, 1,
        "module", PricingMetricsConstants.MODULE, "reason", "BELOW_COST");
  }

  @Test
  void unchangedRateStaysAsIs() {
    Rate sent = storedRateA100();
    Pricing p = stored("p1", "100", "150", null, storedRateA100());

    service.apply(p, List.of(sent), null, CASHIER);

    assertEquals(List.of(storedRateA100()), p.getRates());
  }

  @Test
  void resetRatesRegeneratesNamedRate() {
    Pricing p = stored("p1", "100", "150", null, storedOverrideFivePercent());

    service.apply(p, null, List.of("Rate-A"), OWNER);

    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertEquals(dec("8"), a.getDiscountPercent());
    assertFalse(a.getManual());
  }

  @Test
  void rateWhoseRuleWasDeletedIsKeptAsManual() {
    Rate orphan = storedRateA100();
    orphan.setName("Rate-Old");
    Pricing p = stored("p1", "100", "150", null, storedRateA100(), orphan);

    service.apply(p, null, null, CASHIER);

    Rate old = find(p.getRates(), "Rate-Old");
    assertEquals(dec("83.64"), old.getPrice());
    assertTrue(old.getManual());
  }

  @Test
  void legacyHandTypedRateKeepsTypedPriceWithoutGrant() {
    Pricing p = stored("p1", "100", "150", null, storedRateA100(), namePrice("Rate-X", "70"));

    service.apply(p, List.of(namePrice("Rate-A", "83.64"), namePrice("Rate-X", "72.50")), null, CASHIER);

    Rate x = find(p.getRates(), "Rate-X");
    assertEquals(dec("72.50"), x.getPrice());
    assertTrue(x.getManual());
    verify(port, never()).canEditRates(anyString(), anyString());
  }

  @Test
  void defaultRateOnSkippedRuleFallsBackWithoutThrowing() {
    for (int run = 0; run < 2; run++) {
      Pricing p = stored("p1", "100", "150", "90", storedRateA100());
      p.setDefaultRate("Rate-A");
      p.setSellingPrice(dec("83.64"));

      service.apply(p, null, null, CASHIER);

      assertEquals(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL, p.getDefaultRate());
      assertEquals(dec("100"), p.getSellingPrice());
    }
    verify(metrics, org.mockito.Mockito.times(2)).increment(
        PricingMetricsConstants.RATE_DEFAULT_FALLBACK_TOTAL, 1, "module", PricingMetricsConstants.MODULE);
  }

  @Test
  void sellingPriceFollowsRegeneratedDefaultRate() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(rateM()));
    Pricing p = stored("p1", "100", "200", null);
    p.setDefaultRate("Rate-M");
    p.setRates(new ArrayList<>(List.of(Rate.builder().name("Rate-M").price(dec("135.00"))
        .base(PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE).baseValue(dec("150")).discountPercent(dec("10"))
        .manual(false).gstMode(PricingConstants.GST_MODE_INCLUSIVE).build())));
    p.setSellingPrice(dec("135.00"));

    service.apply(p, null, null, CASHIER);

    assertEquals(dec("180.00"), find(p.getRates(), "Rate-M").getPrice());
    assertEquals(dec("180.00"), p.getSellingPrice());
  }

  @Test
  void ptrEditRecalculatesNonManualRates() {
    Pricing p = stored("p1", "110", "150", null, storedRateA100());

    service.apply(p, null, null, CASHIER);

    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("92.00"), a.getPrice());
    assertEquals(dec("110"), a.getBaseValue());
  }

  @Test
  void baseChangeKeepsOverrideTermsAndRegeneratesOthers() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(rateA(), rateM()));
    Rate m = Rate.builder().name("Rate-M").price(dec("135.00"))
        .base(PricingConstants.RATE_BASE_MAXIMUM_RETAIL_PRICE).baseValue(dec("150")).discountPercent(dec("10"))
        .manual(false).gstMode(PricingConstants.GST_MODE_INCLUSIVE).build();
    Pricing p = stored("p1", "105", "200", null, storedOverrideFivePercent(), m);

    service.apply(p, null, null, CASHIER);

    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("90.68"), a.getPrice());
    assertEquals(dec("5"), a.getDiscountPercent());
    assertTrue(a.getManual());
    Rate rm = find(p.getRates(), "Rate-M");
    assertEquals(dec("180.00"), rm.getPrice());
    assertFalse(rm.getManual());
  }

  @Test
  void legacyRateLaterCoveredByRuleIsRegenerated() {
    Rate legacy = namePrice("Rate-A", "70");
    legacy.setManual(true);
    Pricing p = stored("p1", "100", "150", null, legacy);

    service.apply(p, null, null, CASHIER);

    Rate a = find(p.getRates(), "Rate-A");
    assertEquals(dec("83.64"), a.getPrice());
    assertEquals(dec("8"), a.getDiscountPercent());
    assertFalse(a.getManual());
  }

  @Test
  void defaultRateOnSellingPriceBasedRateFallsBack() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(new com.inventory.pricing.domain.model.RateRuleSpec(
        "Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, dec("10"), null)));
    Pricing p = stored("p1", "100", "100", null);
    p.setDefaultRate("Rate-S");
    p.setSellingPrice(dec("100"));

    service.apply(p, null, null, CASHIER);

    assertEquals(dec("90.00"), find(p.getRates(), "Rate-S").getPrice());
    assertEquals(PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL, p.getDefaultRate());
    assertEquals(dec("100"), p.getSellingPrice());
    verify(metrics).increment(
        PricingMetricsConstants.RATE_DEFAULT_FALLBACK_TOTAL, 1, "module", PricingMetricsConstants.MODULE);
  }

  @Test
  void echoedZeroDiscIsNotAnOverride() {
    when(port.rulesFor(SHOP)).thenReturn(List.of(new com.inventory.pricing.domain.model.RateRuleSpec(
        "Rate-D", PricingConstants.RATE_BASE_PRICE_TO_RETAIL, null, RateGenerationTestFixtures.tenPlusOne())));
    Rate storedD = RateGenerationTestFixtures.generated("Rate-D", "90.91", PricingConstants.RATE_BASE_PRICE_TO_RETAIL,
        "100", null, RateGenerationTestFixtures.tenPlusOne(), false, PricingConstants.GST_MODE_EXCLUSIVE);
    Pricing p = stored("p1", "100", "150", null, storedD);

    service.apply(p, List.of(withDisc("Rate-D", "90.91", "0")), null, CASHIER);

    Rate d = find(p.getRates(), "Rate-D");
    assertFalse(d.getManual());
    assertNull(d.getDiscountPercent());
    verify(port, never()).canEditRates(anyString(), anyString());
  }

  @Test
  void grantIsCheckedAgainstTheUserAndShop() {
    Pricing p = stored("p1", "100", "150", null, storedRateA100());

    service.apply(p, List.of(withDisc("Rate-A", "83.64", "5")), null, OWNER);

    verify(port).canEditRates(eq(OWNER), eq(SHOP));
  }
}
