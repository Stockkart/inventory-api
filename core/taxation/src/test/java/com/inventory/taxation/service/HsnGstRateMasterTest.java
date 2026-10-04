package com.inventory.taxation.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/** The shipped rate table, as read from the CBIC notifications. */
class HsnGstRateMasterTest {

  private final HsnGstRateMaster master =
      new HsnGstRateMaster(new ObjectMapper(), new DefaultResourceLoader());

  private boolean allows(String hsn, String rate) {
    return master.rateFor(hsn).orElseThrow().allows(new BigDecimal(rate));
  }

  @Test
  void medicinesAreFivePercentOrExempt() {
    assertTrue(allows("30049011", "5"));
    assertTrue(allows("30049011", "0"));
    assertFalse(allows("30049011", "18"));
    assertTrue(master.rateFor("30049011").orElseThrow().isAuthoritative());
  }

  @Test
  void aHeadingSpanningTwoEntriesAllowsBoth() {
    // 3306: toothpaste 5% (Schedule I), other oral-care preparations 18% (Schedule II).
    assertTrue(allows("33061020", "5"));
    assertTrue(allows("33061020", "18"));
    assertFalse(allows("33061020", "12"));
  }

  @Test
  void amendmentsAreApplied() {
    // 19/2025: biris moved to 18%; 01/2026: fruit-juice drinks 2202 99 21 at 5%.
    assertTrue(allows("24031921", "18"));
    assertTrue(allows("22029921", "5"));
  }

  @Test
  void anHsnNotInTheNotificationsHasNoEntry() {
    assertTrue(master.rateFor("99999999").isEmpty());
  }
}
