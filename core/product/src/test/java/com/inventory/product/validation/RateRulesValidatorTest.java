package com.inventory.product.validation;

import static com.inventory.product.service.RateRulesTestFixtures.rateA;
import static com.inventory.product.service.RateRulesTestFixtures.rateS;
import static com.inventory.product.service.RateRulesTestFixtures.rule;
import static com.inventory.product.service.RateRulesTestFixtures.tenPlusOne;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.constants.PricingConstants;
import com.inventory.pricing.validation.PricingValidator;
import com.inventory.product.domain.model.enums.ShopType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateRulesValidatorTest {

  private static final String PTR = PricingConstants.RATE_BASE_PRICE_TO_RETAIL;

  private final RateRulesValidator validator = new RateRulesValidator(new PricingValidator());

  private BaseException rejected(ShopType type, com.inventory.product.rest.dto.response.RateRuleDto... rules) {
    return assertThrows(BaseException.class, () -> validator.validate(List.of(rules), type));
  }

  @Test
  void acceptsTheWorkedExampleForADistributor() {
    assertDoesNotThrow(() -> validator.validate(List.of(rateA()), ShopType.DISTRIBUTOR));
  }

  @Test
  void acceptsTheSellingPriceRuleForARetailer() {
    assertDoesNotThrow(() -> validator.validate(List.of(rateS()), ShopType.RETAILER));
  }

  @Test
  void acceptsAnEmptyListToClearTheRules() {
    assertDoesNotThrow(() -> validator.validate(List.of(), ShopType.WHOLESALER));
  }

  @Test
  void rejectsRuleWithNeitherDiscNorScheme() {
    BaseException e = rejected(ShopType.DISTRIBUTOR, rule("Rate-A", PTR, null, null));
    assertEquals(ErrorCode.RATE_RULE_INVALID, e.getErrorCode());
  }

  @Test
  void rejectsDiscOutsideZeroToHundred() {
    assertEquals(ErrorCode.RATE_RULE_INVALID,
        rejected(ShopType.DISTRIBUTOR, rule("Rate-A", PTR, "-0.01", null)).getErrorCode());
    assertEquals(ErrorCode.RATE_RULE_INVALID,
        rejected(ShopType.DISTRIBUTOR, rule("Rate-A", PTR, "100.01", null)).getErrorCode());
  }

  @Test
  void acceptsDiscAtTheInclusiveEnds() {
    assertDoesNotThrow(() -> validator.validate(
        List.of(rule("Zero", PTR, "0", null), rule("Full", PTR, "100", null)), ShopType.DISTRIBUTOR));
  }

  @Test
  void rejectsDuplicateNamesIgnoringCase() {
    BaseException e = rejected(ShopType.DISTRIBUTOR,
        rule("Rate-A", PTR, "8", null), rule("rate-a", PTR, "5", null));
    assertEquals(ErrorCode.RATE_RULE_INVALID, e.getErrorCode());
  }

  @Test
  void rejectsReservedNames() {
    for (String reserved : List.of("priceToRetail", "maximumRetailPrice", "costPrice", "COSTPRICE")) {
      assertEquals(ErrorCode.RATE_RULE_INVALID,
          rejected(ShopType.DISTRIBUTOR, rule(reserved, PTR, "8", null)).getErrorCode(), reserved);
    }
  }

  @Test
  void rejectsPtrBaseForRetailer() {
    BaseException e = rejected(ShopType.RETAILER, rule("Rate-A", PTR, "8", null));
    assertEquals(ErrorCode.RATE_RULE_BASE_NOT_ALLOWED, e.getErrorCode());
  }

  @Test
  void rejectsSellingPriceBaseForWholesaler() {
    BaseException e = rejected(ShopType.WHOLESALER,
        rule("Rate-S", PricingConstants.RATE_BASE_SELLING_PRICE, "5", null));
    assertEquals(ErrorCode.RATE_RULE_BASE_NOT_ALLOWED, e.getErrorCode());
  }

  @Test
  void rejectsBlankName() {
    assertEquals(ErrorCode.RATE_RULE_INVALID,
        rejected(ShopType.DISTRIBUTOR, rule("  ", PTR, "8", null)).getErrorCode());
    assertEquals(ErrorCode.RATE_RULE_INVALID,
        rejected(ShopType.DISTRIBUTOR, rule(null, PTR, "8", null)).getErrorCode());
  }

  @Test
  void rejectsAnUnknownBase() {
    assertEquals(ErrorCode.RATE_RULE_INVALID,
        rejected(ShopType.DISTRIBUTOR, rule("Rate-A", "costPrice", "8", null)).getErrorCode());
  }

  @Test
  void rejectsBadSchemeShape() {
    assertThrows(BaseException.class, () -> validator.validate(List.of(
        rule("Rate-A", PTR, null, new SchemeDto(PricingConstants.SCHEME_TYPE_FIXED_UNITS, 0, 1, null))),
        ShopType.DISTRIBUTOR));
    assertThrows(BaseException.class, () -> validator.validate(List.of(
        rule("Rate-A", PTR, null, new SchemeDto(PricingConstants.SCHEME_TYPE_PERCENTAGE, null, null,
            new BigDecimal("100")))), ShopType.DISTRIBUTOR));
    assertThrows(BaseException.class, () -> validator.validate(List.of(
        rule("Rate-A", PTR, null, new SchemeDto("BOGUS", null, null, null))), ShopType.DISTRIBUTOR));
  }

  @Test
  void acceptsASchemeOnlyRule() {
    assertDoesNotThrow(() -> validator.validate(
        List.of(rule("Rate-A", PTR, null, tenPlusOne())), ShopType.DISTRIBUTOR));
  }
}
