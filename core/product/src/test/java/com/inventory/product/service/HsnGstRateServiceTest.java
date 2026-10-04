package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.product.rest.dto.response.HsnGstRatesResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class HsnGstRateServiceTest {

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  @Test
  void offersEveryRateTheHsnAllowsSplitIntoHalves() {
    HsnGstRateService service = new HsnGstRateService(
        new HsnGstRateMaster(new ObjectMapper(), new DefaultResourceLoader()));

    HsnGstRatesResponse out = service.ratesFor("33061020");

    assertEquals("3306", out.getMatchedHsn());
    assertEquals(2, out.getRates().size());
    HsnGstRatesResponse.Option five = out.getRates().get(0);
    assertEquals(0, bd("5").compareTo(five.getGstRate()));
    assertEquals(0, bd("2.5").compareTo(five.getCgst()));
    assertEquals(0, bd("2.5").compareTo(five.getSgst()));
    assertEquals(0, bd("18").compareTo(out.getRates().get(1).getGstRate()));
    assertTrue(out.getRef().contains("9/2025 Sch I S.No 246"));
  }

  @Test
  void aQuarterPercentSplitsWithoutRounding() {
    HsnGstRateService service = new HsnGstRateService(new HsnGstRateMaster(Map.of(
        "7102", new HsnGstRateMaster.Entry("7102", List.of(bd("0.25")), "verified", "Sch V"))));

    HsnGstRatesResponse.Option option = service.ratesFor("71021000").getRates().get(0);

    assertEquals(0, bd("0.125").compareTo(option.getCgst()));
  }

  @Test
  void anHsnNotOnFileOffersNothing() {
    HsnGstRateService service = new HsnGstRateService(new HsnGstRateMaster(Map.of(
        "3401", new HsnGstRateMaster.Entry("3401", List.of(bd("18")), "unchecked", ""))));

    HsnGstRatesResponse out = service.ratesFor("99999999");
    assertTrue(out.getRates().isEmpty());
    assertNull(out.getMatchedHsn());
    assertTrue(service.ratesFor("34011110").getRates().isEmpty());
  }
}
