package com.inventory.hr.utils;

/**
 * How bank account numbers and UPI ids look when masked (R4.5). Kept out of the MapStruct mapper
 * so MapStruct does not pick these String-to-String methods for ordinary String fields.
 */
public final class EmployeeMaskingUtils {

  private EmployeeMaskingUtils() {}

  /** {@code XXXXXX1234}: last 4 characters only; 4 or fewer become {@code XXXX}; null stays null. */
  public static String maskAccount(String account) {
    if (account == null || account.isBlank()) {
      return null;
    }
    String a = account.trim();
    if (a.length() <= 4) {
      return "XXXX";
    }
    return "XXXXXX" + a.substring(a.length() - 4);
  }

  /**
   * {@code ra***@okaxis}: at most the first 2 characters of the handle, and always fewer than the
   * whole handle, then the bank domain. Without an {@code @} it becomes {@code ***}.
   */
  public static String maskUpi(String upi) {
    if (upi == null || upi.isBlank()) {
      return null;
    }
    String u = upi.trim();
    int at = u.indexOf('@');
    if (at < 0) {
      return "***";
    }
    int keep = Math.min(2, Math.max(0, at - 1));
    return u.substring(0, keep) + "***" + u.substring(at);
  }
}
