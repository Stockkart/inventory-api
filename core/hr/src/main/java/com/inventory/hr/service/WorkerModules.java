package com.inventory.hr.service;

import java.util.List;

/**
 * The modules the Stockkart Team app shows on Home, decided by the server per shop
 * (worker-app R6.4, D10, DD10). The app renders exactly this list, in this order, and shows an
 * unknown key as "Update the app to use this" (R6.6).
 *
 * <p>A constant for every shop in this version. A per-shop switch can replace the body of {@link
 * #forShop(String)} later without an app release.
 */
public final class WorkerModules {

  public static final String ATTENDANCE = "ATTENDANCE";
  public static final String HISTORY = "HISTORY";
  public static final String PROFILE = "PROFILE";

  /** The list every shop gets today; Attendance first because its tile is first and largest. */
  static final List<String> DEFAULT = List.of(ATTENDANCE, HISTORY, PROFILE);

  private WorkerModules() {}

  /**
   * The module keys for the worker Home of {@code shopId}, in display order, without duplicates.
   *
   * @param shopId the shop the worker is ACTIVE in; unused in this version but part of the contract
   */
  public static List<String> forShop(String shopId) {
    return DEFAULT;
  }
}
