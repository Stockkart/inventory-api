package com.inventory.plan.domain.model;

/**
 * How a feature is provisioned on a tier.
 *
 * <p>{@link #LIMITED} and {@link #ADVANCED} exist because some features are graded rather than
 * on/off — Access Control is absent on Starter, limited on Professional and advanced on Enterprise,
 * which a boolean could not express.
 */
public enum FeatureAvailability {
  EXCLUDED,
  INCLUDED,
  LIMITED,
  ADVANCED
}
