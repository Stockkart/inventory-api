package com.inventory.pricing.rest.dto.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The landed cost has to follow the fields it is derived from. It is stored as well as derivable,
 * and a stored figure that no longer matches its own inputs is the more dangerous of the two: it
 * reads as authoritative and is wrong everywhere at once -- margin, valuation, COGS.
 */
class PricingReadDtoEffectiveCostTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static PricingReadDto dto(
      String costPrice, String stored, String purchaseAddlDiscount, SchemeDto purchaseScheme) {
    PricingReadDto d = new PricingReadDto();
    d.setCostPrice(costPrice == null ? null : bd(costPrice));
    d.setEffectiveCostPrice(stored == null ? null : bd(stored));
    d.setPurchaseAdditionalDiscount(purchaseAddlDiscount == null ? null : bd(purchaseAddlDiscount));
    d.setPurchaseScheme(purchaseScheme);
    return d;
  }

  private static SchemeDto fixedUnits(int payFor, int free) {
    SchemeDto s = new SchemeDto();
    s.setSchemeType("FIXED_UNITS");
    s.setSchemePayFor(payFor);
    s.setSchemeFree(free);
    return s;
  }

  /**
   * The CHARAK case: a trade discount was added to a record whose stored landed cost still said the
   * full list price, so the cart went on charging cost against an undiscounted figure.
   */
  @Test
  void prefersTheInputsWhenTheStoredFigureNoLongerMatchesThem() {
    PricingReadDto d = dto("379.43", "379.43", "10", null);

    assertEquals(0, bd("341.4870").compareTo(d.resolveEffectiveCostPrice()),
        "a stored landed cost that ignores its own discount must not win");
  }

  @Test
  void appliesBothTheSchemeAndTheDiscountWhenBothArePresent() {
    PricingReadDto d = dto("69.33", "63.55", "10", fixedUnits(11, 1));

    assertEquals(0, bd("57.1973").compareTo(d.resolveEffectiveCostPrice()));
  }

  @Test
  void agreesWithTheStoredFigureWhenItIsAlreadyCorrect() {
    PricingReadDto d = dto("100.00", "90.0000", "10", null);

    assertEquals(0, bd("90.0000").compareTo(d.resolveEffectiveCostPrice()));
  }

  /** Records written before the field existed still derive on the fly, as they always did. */
  @Test
  void derivesWhenNothingIsStored() {
    PricingReadDto d = dto("100.00", null, "10", null);

    assertEquals(0, bd("90.0000").compareTo(d.resolveEffectiveCostPrice()));
  }

  @Test
  void returnsTheCostUntouchedWhenNoDiscountOrSchemeReducesIt() {
    PricingReadDto d = dto("100.00", null, null, null);

    assertEquals(0, bd("100.00").compareTo(d.resolveEffectiveCostPrice()));
  }

  /** Nothing to derive from: the stored figure is the only answer left, so it stands. */
  @Test
  void fallsBackToTheStoredFigureWhenThereIsNoCostToDeriveFrom() {
    PricingReadDto d = dto(null, "55.21", "10", null);

    assertEquals(0, bd("55.21").compareTo(d.resolveEffectiveCostPrice()));
  }

  @Test
  void returnsNullWhenThereIsNeitherACostNorAStoredFigure() {
    assertNull(dto(null, null, "10", null).resolveEffectiveCostPrice());
  }
}
