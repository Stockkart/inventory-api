package com.inventory.pluginengine.cards;

import java.util.Map;
import java.util.Set;

/**
 * Describes one place in the UI that renders product cards.
 *
 * @param surfaceId stable id exchanged with the frontend, e.g. {@code product-search}
 * @param label human-readable name shown as a tab in the configuration screen
 * @param billingModeAware when true the surface keeps one layout per {@link CardVariant}; when
 *     false it keeps a single layout stored under {@link CardVariant#REGULAR}
 * @param excludedFieldKeys catalog keys that make no sense on this surface; the validator rejects
 *     them and the resolver drops them
 * @param coreDefaults the built-in layout per variant; a {@link CardSurfaceContributor} may
 *     override these per vertical
 */
public record CardSurfaceDefinition(
    String surfaceId,
    String label,
    boolean billingModeAware,
    Set<String> excludedFieldKeys,
    Map<CardVariant, CardLayout> coreDefaults) {

  public CardSurfaceDefinition {
    if (surfaceId == null || surfaceId.isBlank()) {
      throw new IllegalArgumentException("surfaceId must not be blank");
    }
    surfaceId = surfaceId.trim();
    label = label == null || label.isBlank() ? surfaceId : label.trim();
    excludedFieldKeys = excludedFieldKeys == null ? Set.of() : Set.copyOf(excludedFieldKeys);
    coreDefaults = coreDefaults == null ? Map.of() : Map.copyOf(coreDefaults);
  }

  /** The variants this surface keeps a layout for. */
  public Set<CardVariant> variants() {
    return billingModeAware ? Set.of(CardVariant.REGULAR, CardVariant.BASIC) : Set.of(CardVariant.REGULAR);
  }

  /** True when the surface accepts a layout for the given variant. */
  public boolean supports(CardVariant variant) {
    return variant == CardVariant.REGULAR || billingModeAware;
  }

  /** True when the given catalog key may not be used on this surface. */
  public boolean excludes(String fieldKey) {
    return fieldKey != null && excludedFieldKeys.contains(fieldKey);
  }

  /** The built-in layout for a variant, falling back to REGULAR, then to an empty layout. */
  public CardLayout coreDefault(CardVariant variant) {
    CardLayout layout = coreDefaults.get(variant);
    if (layout == null) {
      layout = coreDefaults.get(CardVariant.REGULAR);
    }
    return layout == null ? CardLayout.EMPTY : layout;
  }
}
