package com.inventory.plan.utils;

import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.utils.constants.PricingConstants;
import java.util.Optional;
import java.util.Set;

/**
 * Whether an add-on can be bought on a plan: the one rule for checkout, quote and the add-on
 * lists the apps show ({@code eligible} / {@code ineligibleReason}).
 */
public final class AddOnEligibility {

  private AddOnEligibility() {}

  /**
   * Why {@code addOn} cannot be bought on {@code plan}, or empty when it can. {@code heldOnce} holds
   * the codes of live add-ons the shop already has that are not bought again on top (empty for a
   * plan cart, whose new grant runs to the new term's end).
   */
  public static Optional<String> ineligibleReason(Plan plan, AddOn addOn, Set<String> heldOnce) {
    if (!addOn.isActive()) {
      return Optional.of(addOn.getName() + " is no longer sold");
    }
    if (addOn.getGrantType() == AddOnGrantType.SMS) {
      return Optional.of(PricingConstants.MSG_SMS_COMING_SOON);
    }
    // A legacy plan (no catalogue code) is grandfathered into every feature, unlimited users and
    // unlimited OCR, so no add-on adds anything to it.
    if (plan.getCode() == null) {
      return Optional.of(PricingConstants.MSG_LEGACY_PLAN_HAS_EVERYTHING);
    }
    if (addOn.getGrantType() == AddOnGrantType.FEATURE
        && plan.getFeatures() != null && plan.getFeatures().contains(addOn.getGrantsFeature())) {
      return Optional.of(plan.getPlanName() + " already includes " + addOn.getName());
    }
    if (addOn.getGrantType() == AddOnGrantType.SEATS && plan.isUnlimited()) {
      return Optional.of(plan.getPlanName() + " already has unlimited users");
    }
    if (isHeldOnce(addOn, heldOnce)) {
      return Optional.of("You already have " + addOn.getName());
    }
    return Optional.empty();
  }

  /** A non-stackable add-on (other than OCR credits) the shop already holds live. */
  public static boolean isHeldOnce(AddOn addOn, Set<String> heldOnce) {
    return !addOn.isStackable() && addOn.getGrantType() != AddOnGrantType.OCR_CREDITS
        && heldOnce.contains(addOn.getCode());
  }
}
