package com.inventory.user.utils;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.util.StringUtils;

/**
 * The drug licence (D.L.) rules shared by customers and vendors.
 *
 * <p>A party with a D.L. No. must also have its expiry date; a party without one needs neither. A
 * licence is expired from the day after its expiry date, by the Indian calendar.
 */
public final class DrugLicence {

  public static final String EXPIRY_REQUIRED =
      "Enter the D.L. expiry date, or clear the D.L. No.";

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private DrugLicence() {}

  /** True when a D.L. No. is present but its expiry date is not. */
  public static boolean missingExpiry(String dlNo, LocalDate dlExpiry) {
    return StringUtils.hasText(dlNo) && dlExpiry == null;
  }

  /** The expiry to store: none when there is no D.L. No. to expire. */
  public static LocalDate expiryToStore(String dlNo, LocalDate dlExpiry) {
    return StringUtils.hasText(dlNo) ? dlExpiry : null;
  }

  /** True when the licence ran out before {@code today}; null (no D.L.) when there is no expiry. */
  public static Boolean isExpired(LocalDate dlExpiry, LocalDate today) {
    return dlExpiry == null ? null : dlExpiry.isBefore(today);
  }

  /** Today's date in India, the calendar licences are issued and checked by. */
  public static LocalDate today() {
    return LocalDate.now(INDIA);
  }
}
