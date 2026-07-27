package com.inventory.plan.migration;

import com.inventory.plan.domain.model.FeatureAvailability;
import com.inventory.plan.domain.model.PlanFeature;
import com.inventory.plan.domain.model.PlanTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the seeded feature matrix to the agreed plan sheet. */
class PlanCatalogSeederTest {

  private final PlanCatalogSeeder seeder = new PlanCatalogSeeder();

  private FeatureAvailability availability(PlanTier tier, String key) {
    PlanFeature match = seeder.features(tier).stream()
        .filter(f -> f.getKey().equals(key))
        .findFirst()
        .orElse(null);
    assertNotNull(match, "missing feature row: " + key);
    return match.getAvailability();
  }

  @Test
  void everyTierExposesTheSameFeatureRows() {
    List<PlanFeature> starter = seeder.features(PlanTier.STARTER);
    List<PlanFeature> professional = seeder.features(PlanTier.PROFESSIONAL);
    List<PlanFeature> enterprise = seeder.features(PlanTier.ENTERPRISE);

    assertEquals(23, starter.size());
    assertEquals(starter.size(), professional.size());
    assertEquals(starter.size(), enterprise.size());

    // Rows must align by key so the comparison matrix lines up across columns.
    for (int i = 0; i < starter.size(); i++) {
      assertEquals(starter.get(i).getKey(), professional.get(i).getKey());
      assertEquals(starter.get(i).getKey(), enterprise.get(i).getKey());
    }
  }

  @Test
  void sortOrderIsAscendingAndDense() {
    List<PlanFeature> features = seeder.features(PlanTier.STARTER);
    for (int i = 0; i < features.size(); i++) {
      assertEquals(i, features.get(i).getSortOrder());
    }
  }

  @Test
  void baselineFeaturesAreOnEveryTier() {
    for (PlanTier tier : PlanTier.values()) {
      assertEquals(FeatureAvailability.INCLUDED, availability(tier, "PRODUCTS_AND_SALES"));
      assertEquals(FeatureAvailability.INCLUDED, availability(tier, "ANALYTICS_DASHBOARD"));
      assertEquals(FeatureAvailability.INCLUDED, availability(tier, "CALCULATOR"));
    }
  }

  @Test
  void professionalPlusFeaturesAreExcludedOnStarterOnly() {
    for (String key : List.of(
        "CREDIT_BALANCE", "ACCOUNTING", "BARCODE_GENERATOR", "REMINDER_LOW_STOCK_NOTIFICATION")) {
      assertEquals(FeatureAvailability.EXCLUDED, availability(PlanTier.STARTER, key), key);
      assertEquals(FeatureAvailability.INCLUDED, availability(PlanTier.PROFESSIONAL, key), key);
      assertEquals(FeatureAvailability.INCLUDED, availability(PlanTier.ENTERPRISE, key), key);
    }
  }

  @Test
  void enterpriseOnlyFeaturesAreExcludedBelowEnterprise() {
    for (String key : List.of("MARKETING_CAMPAIGN", "SALARY_SCREEN", "BIOMETRIC_ATTENDANCE")) {
      assertEquals(FeatureAvailability.EXCLUDED, availability(PlanTier.STARTER, key), key);
      assertEquals(FeatureAvailability.EXCLUDED, availability(PlanTier.PROFESSIONAL, key), key);
      assertEquals(FeatureAvailability.INCLUDED, availability(PlanTier.ENTERPRISE, key), key);
    }
  }

  @Test
  void accessControlIsGradedRatherThanOnOff() {
    assertEquals(FeatureAvailability.EXCLUDED, availability(PlanTier.STARTER, "ACCESS_CONTROL"));
    assertEquals(FeatureAvailability.LIMITED, availability(PlanTier.PROFESSIONAL, "ACCESS_CONTROL"));
    assertEquals(FeatureAvailability.ADVANCED, availability(PlanTier.ENTERPRISE, "ACCESS_CONTROL"));
  }

  @Test
  void everyRowHasANonBlankLabel() {
    for (PlanFeature feature : seeder.features(PlanTier.ENTERPRISE)) {
      assertTrue(feature.getLabel() != null && !feature.getLabel().isBlank(), feature.getKey());
    }
  }
}
