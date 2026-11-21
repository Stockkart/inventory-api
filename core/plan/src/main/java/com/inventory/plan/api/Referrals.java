package com.inventory.plan.api;

/** Referral attribution at shop registration, for the module that owns shops. */
public interface Referrals {

  /**
   * Rejects a registration that names a referral code no shop owns, or an over-long referrer name,
   * before the shop is created.
   *
   * @throws com.inventory.common.exception.ValidationException when the code or name is not valid
   */
  void checkReferral(String referredByCode, String referredByName);

  /**
   * Records who referred a newly registered shop. Does nothing when neither code nor name is given;
   * a second call for the same shop keeps the first attribution.
   */
  void attribute(String shopId, String registeringUserId, String referredByCode, String referredByName);
}
