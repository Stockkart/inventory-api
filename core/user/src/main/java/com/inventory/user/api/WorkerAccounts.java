package com.inventory.user.api;

import java.util.List;
import java.util.Optional;

/**
 * What HR needs from the user module for the Stockkart Team app (worker-app R3, R4.5, R5.1, R11.5;
 * design DD1, DD2): the requesting account's details for the request snapshot, the shops an owner
 * e-mail owns for the shop lookup, the one-shop lock on a WORKER account, the WORKER membership
 * written on approval, and whether the account has a photo. HR depends on user, so this is a plain
 * downward call through the user module's {@code api} package (module-boundaries B2); the
 * implementation is {@code com.inventory.user.service.api.WorkerAccountsImpl}.
 */
public interface WorkerAccounts {

  /** The account by id; empty when there is none. */
  Optional<AccountView> account(String userId);

  /**
   * The shops owned by the account with this e-mail (an active OWNER membership), with the owner's
   * display name for the first-name greeting. Empty for every miss: no such account, an account
   * that owns nothing. The caller must never tell those cases apart (R3.3, R11.4).
   */
  List<OwnedShop> shopsOwnedBy(String ownerEmail);

  /**
   * Makes {@code shopId} the WORKER account's one shop if it has none yet (design DD1), in one
   * conditional write. Idempotent for the same shop.
   *
   * @return true when the account's shop is {@code shopId} after the call; false when the account
   *     is not a WORKER account or another shop holds it
   */
  boolean claimShop(String shopId, String userId);

  /**
   * Frees the WORKER account from {@code shopId} and ends its WORKER membership there (R5.2, R5.3).
   * A staff account is left untouched (R13.4). Safe to call twice.
   *
   * @return true when the account's shop was cleared by this call
   */
  boolean releaseShop(String shopId, String userId);

  /**
   * Adds an active WORKER membership (relationship INVITED) for the account in the shop; nothing
   * happens when an active membership already exists.
   */
  void addWorkerMembership(String shopId, String userId);

  /** True when the account has a profile photo (the join-request snapshot's {@code hasPhoto}). */
  boolean hasPhoto(String userId);

  /**
   * The account's own name and phone (worker-app R8.2) under the sign-up rules (R1.2): name 2–100
   * characters after trimming, phone a 10-digit Indian mobile. The e-mail is never changed here.
   * The linked employee, when one exists, is HR's to update in the same call.
   *
   * @return the account as saved
   * @throws com.inventory.common.exception.ValidationException when the name or phone breaks the
   *     rules
   * @throws com.inventory.common.exception.ResourceNotFoundException when there is no such account
   */
  AccountView updateProfile(String userId, String name, String phone);

  /**
   * An account as HR sees it.
   *
   * @param role the role name ({@code WORKER}, {@code OWNER}, …)
   * @param shopId the account's active shop; for a WORKER account its one shop, null when none
   * @param worker true for a WORKER account (the role HR may claim a shop for)
   */
  record AccountView(String userId, String name, String email, String phone, String role,
      String shopId, boolean worker) {}

  /** One shop an owner e-mail owns, with the owner's display name. */
  record OwnedShop(String shopId, String shopName, String ownerName) {}
}
