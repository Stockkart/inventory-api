package com.inventory.pricing.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.rest.dto.response.SchemeDto;
import com.inventory.pricing.utils.RateTermsText;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PricingMapperRateFieldsTest {

  private final PricingMapper mapper = new PricingMapperImpl();

  private static Rate fullRate() {
    return Rate.builder()
        .name("Rate-A").price(new BigDecimal("84.18")).base("priceToRetail")
        .baseValue(new BigDecimal("100.00")).discountPercent(new BigDecimal("8"))
        .scheme(new Scheme("FIXED_UNITS", 10, 1, null)).manual(Boolean.TRUE)
        .gstMode("EXCLUSIVE").build();
  }

  @Test
  void mapperCopiesEveryRateField() {
    RateDto dto = mapper.toRateDto(fullRate());

    assertEquals("Rate-A", dto.getName());
    assertEquals(new BigDecimal("84.18"), dto.getPrice());
    assertEquals("priceToRetail", dto.getBase());
    assertEquals(new BigDecimal("100.00"), dto.getBaseValue());
    assertEquals(new BigDecimal("8"), dto.getDiscountPercent());
    assertEquals(new SchemeDto("FIXED_UNITS", 10, 1, null), dto.getScheme());
    assertEquals(Boolean.TRUE, dto.getManual());
    assertEquals("EXCLUSIVE", dto.getGstMode());
    assertEquals("PTR 100.00 − 8% · 10+1", dto.getTermsText());

    assertEquals(fullRate(), mapper.toRate(dto));
    assertEquals(
        java.util.List.of(fullRate()), mapper.toRates(mapper.toRateDtos(java.util.List.of(fullRate()))));
  }

  @Test
  void termsTextForDiscAndScheme() {
    assertEquals("PTR 100.00 − 8% · 10+1", RateTermsText.of(fullRate()));
  }

  @Test
  void termsTextForDiscOnly() {
    Rate rate = Rate.builder().name("R").price(new BigDecimal("112.50")).base("maximumRetailPrice")
        .baseValue(new BigDecimal("150")).discountPercent(new BigDecimal("25")).build();
    assertEquals("MRP 150.00 − 25%", RateTermsText.of(rate));
  }

  @Test
  void termsTextForSellingPriceAndPercentageScheme() {
    Rate rate = Rate.builder().name("R").price(new BigDecimal("90")).base("sellingPrice")
        .baseValue(new BigDecimal("100"))
        .scheme(new Scheme("PERCENTAGE", null, null, new BigDecimal("10"))).build();
    assertEquals("SP 100.00 · 10% scheme", RateTermsText.of(rate));
  }

  @Test
  void legacyRateWithOnlyNameAndPriceStaysLegacy() {
    RateDto dto = mapper.toRateDto(new Rate("Rate-A", new BigDecimal("90")));
    assertNull(dto.getBase());
    assertNull(dto.getBaseValue());
    assertNull(dto.getDiscountPercent());
    assertNull(dto.getScheme());
    assertNull(dto.getManual());
    assertNull(dto.getGstMode());
    assertNull(dto.getTermsText());
    assertNull(RateTermsText.of(new Rate("Rate-A", new BigDecimal("90"))));
  }
}
