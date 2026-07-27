package com.inventory.plan.migration;

import com.inventory.plan.domain.model.FeatureAvailability;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanBillingPeriod;
import com.inventory.plan.domain.model.PlanFeature;
import com.inventory.plan.domain.model.PlanKind;
import com.inventory.plan.domain.model.PlanTier;
import com.inventory.plan.domain.repository.PlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeds the plan catalog: three subscription tiers, six add-ons and three OCR top-up packs.
 *
 * <p>Matched by {@code planName}, so a rerun updates catalog fields on the existing document rather
 * than creating duplicates. Crucially it preserves the existing {@code id} — shops reference plans
 * by id, and reseeding with fresh ids would orphan every active subscription.
 *
 * <p>Tiers are chained through {@code linkedId} (Starter to Professional to Enterprise) in a second
 * pass, once all three have ids.
 *
 * <p>Disable with {@code plan.catalog.seed-enabled=false} where the catalog is managed by hand.
 */
@Component
@Slf4j
public class PlanCatalogSeeder {

  private static final String STARTER = "Starter";
  private static final String PROFESSIONAL = "Professional";
  private static final String ENTERPRISE = "Enterprise";

  @Autowired private PlanRepository planRepository;

  @Value("${plan.catalog.seed-enabled:true}")
  private boolean seedEnabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void seedOnStartup() {
    if (!seedEnabled) {
      log.info("Plan catalog seeding disabled (plan.catalog.seed-enabled=false)");
      return;
    }
    try {
      seedTiers();
      seedAddons();
      seedOcrTopups();
      linkTierLadder();
    } catch (Exception e) {
      // A catalog problem must not stop the application from booting.
      log.error("Plan catalog seeding failed: {}", e.getMessage(), e);
    }
  }

  private void seedTiers() {
    upsert(starter());
    upsert(professional());
    upsert(enterprise());
  }

  /** Chains the tiers so {@code linkedId} points at the next higher plan. */
  private void linkTierLadder() {
    Optional<Plan> starter = planRepository.findByPlanName(STARTER);
    Optional<Plan> professional = planRepository.findByPlanName(PROFESSIONAL);
    Optional<Plan> enterprise = planRepository.findByPlanName(ENTERPRISE);

    if (starter.isEmpty() || professional.isEmpty() || enterprise.isEmpty()) {
      log.warn("Skipping tier ladder — one or more tiers missing after seed");
      return;
    }

    link(starter.get(), professional.get().getId());
    link(professional.get(), enterprise.get().getId());
    link(enterprise.get(), null);
  }

  private void link(Plan plan, String nextId) {
    if (java.util.Objects.equals(plan.getLinkedId(), nextId)) {
      return;
    }
    plan.setLinkedId(nextId);
    planRepository.save(plan);
    log.info("Linked plan {} -> {}", plan.getPlanName(), nextId);
  }

  /**
   * Inserts when absent, otherwise copies catalog fields onto the stored document.
   *
   * <p>Only catalog fields are overwritten; {@code id} and {@code linkedId} are left alone so
   * existing subscriptions and any manual ladder edits survive a redeploy.
   */
  private void upsert(Plan desired) {
    Optional<Plan> existing = planRepository.findByPlanName(desired.getPlanName());
    if (existing.isEmpty()) {
      planRepository.save(desired);
      log.info("Seeded plan {}", desired.getPlanName());
      return;
    }

    Plan stored = existing.get();
    stored.setPrice(desired.getPrice());
    stored.setArcPrice(desired.getArcPrice());
    stored.setBestFor(desired.getBestFor());
    stored.setKind(desired.getKind());
    stored.setTier(desired.getTier());
    stored.setTierRank(desired.getTierRank());
    stored.setUserRoles(desired.getUserRoles());
    stored.setUserLimit(desired.getUserLimit());
    stored.setOcrInvoiceLimit(desired.getOcrInvoiceLimit());
    stored.setOcrTopupInvoices(desired.getOcrTopupInvoices());
    stored.setBillingPeriod(desired.getBillingPeriod());
    stored.setPerUnitLabel(desired.getPerUnitLabel());
    stored.setAppliesToPlanIds(desired.getAppliesToPlanIds());
    stored.setFeatures(desired.getFeatures());
    stored.setTrialDays(desired.getTrialDays());
    planRepository.save(stored);
    log.info("Updated catalog fields on plan {}", stored.getPlanName());
  }

  // ---------------------------------------------------------------------------
  // Tiers
  // ---------------------------------------------------------------------------

  private Plan starter() {
    Plan plan = tier(STARTER, 4999, "Small shops", PlanTier.STARTER, 1, 100);
    plan.setUserRoles(List.of("Owner"));
    plan.setUserLimit(1);
    plan.setFeatures(features(PlanTier.STARTER));
    return plan;
  }

  private Plan professional() {
    Plan plan = tier(PROFESSIONAL, 6999, "Growing businesses", PlanTier.PROFESSIONAL, 2, 500);
    plan.setUserRoles(List.of("Owner", "Manager", "Cashier"));
    plan.setUserLimit(3);
    plan.setFeatures(features(PlanTier.PROFESSIONAL));
    return plan;
  }

  private Plan enterprise() {
    Plan plan = tier(ENTERPRISE, 9999, "Medium & large businesses", PlanTier.ENTERPRISE, 3, 2000);
    plan.setUserRoles(List.of("Multiple Role"));
    plan.setFeatures(features(PlanTier.ENTERPRISE));
    return plan;
  }

  private Plan tier(String name, int arcPrice, String bestFor, PlanTier tier, int rank, int ocr) {
    Plan plan = new Plan();
    plan.setPlanName(name);
    plan.setArcPrice(BigDecimal.valueOf(arcPrice));
    plan.setPrice(BigDecimal.ZERO);
    plan.setBestFor(bestFor);
    plan.setKind(PlanKind.PLAN);
    plan.setTier(tier);
    plan.setTierRank(rank);
    plan.setOcrInvoiceLimit(ocr);
    plan.setBillingPeriod(PlanBillingPeriod.YEAR);
    return plan;
  }

  // ---------------------------------------------------------------------------
  // Feature matrix
  // ---------------------------------------------------------------------------

  /** Rows shared by every tier, in display order. Availability varies per tier. */
  private static final String[][] FEATURE_ROWS = {
      {"PRODUCTS_AND_SALES", "Products & Sales"},
      {"PRODUCT_SEARCH", "Product Search"},
      {"STOCK_CORRECTIONS", "Stock Corrections"},
      {"PRICING", "Pricing"},
      {"SCAN_AND_SELL", "Scan & Sell"},
      {"VENDOR_MANAGEMENT", "Vendor Management"},
      {"CUSTOMER_MANAGEMENT", "Customer Management"},
      {"RETURN_TO_CUSTOMER", "Return to Customer"},
      {"RETURN_TO_VENDOR", "Return to Vendor"},
      {"CREDIT_BALANCE", "Credit Balance"},
      {"ACCOUNTING", "Accounting"},
      {"ANALYTICS_DASHBOARD", "Analytics Dashboard"},
      {"TAXES_GST_REPORTS", "Taxes (GST Reports)"},
      {"HISTORY_AUDIT_LOGS", "History / Audit Logs"},
      {"REMINDER", "Reminder"},
      {"INVENTORY_LOW_ALERT", "Inventory Low Alert"},
      {"BARCODE_GENERATOR", "Barcode Generator"},
      {"CALCULATOR", "Calculator"},
      {"REMINDER_LOW_STOCK_NOTIFICATION", "Reminder & Low Stock Notification"},
      {"MARKETING_CAMPAIGN", "Marketing (SMS/WhatsApp Campaign)"},
      {"ACCESS_CONTROL", "Access Control (Roles & Permissions)"},
      {"SALARY_SCREEN", "Salary Screen"},
      {"BIOMETRIC_ATTENDANCE", "Biometric Attendance"},
  };

  /** Professional and above. */
  private static final List<String> PROFESSIONAL_PLUS = List.of(
      "CREDIT_BALANCE",
      "ACCOUNTING",
      "BARCODE_GENERATOR",
      "REMINDER_LOW_STOCK_NOTIFICATION");

  /** Enterprise only. */
  private static final List<String> ENTERPRISE_ONLY = List.of(
      "MARKETING_CAMPAIGN",
      "SALARY_SCREEN",
      "BIOMETRIC_ATTENDANCE");

  /** Package-private for test access. */
  List<PlanFeature> features(PlanTier tier) {
    List<PlanFeature> features = new ArrayList<>(FEATURE_ROWS.length);
    for (int i = 0; i < FEATURE_ROWS.length; i++) {
      String key = FEATURE_ROWS[i][0];
      features.add(new PlanFeature(key, FEATURE_ROWS[i][1], availabilityFor(key, tier), i));
    }
    return features;
  }

  /** Package-private for test access. */
  FeatureAvailability availabilityFor(String key, PlanTier tier) {
    // Access Control is graded rather than on/off.
    if ("ACCESS_CONTROL".equals(key)) {
      return switch (tier) {
        case STARTER -> FeatureAvailability.EXCLUDED;
        case PROFESSIONAL -> FeatureAvailability.LIMITED;
        case ENTERPRISE -> FeatureAvailability.ADVANCED;
      };
    }
    if (ENTERPRISE_ONLY.contains(key)) {
      return tier == PlanTier.ENTERPRISE ? FeatureAvailability.INCLUDED : FeatureAvailability.EXCLUDED;
    }
    if (PROFESSIONAL_PLUS.contains(key)) {
      return tier == PlanTier.STARTER ? FeatureAvailability.EXCLUDED : FeatureAvailability.INCLUDED;
    }
    return FeatureAvailability.INCLUDED;
  }

  // ---------------------------------------------------------------------------
  // Add-ons and OCR top-ups
  // ---------------------------------------------------------------------------

  private void seedAddons() {
    upsert(addon("Additional User", 500, "user"));
    upsert(usageAddon("SMS Pack"));
    upsert(addon("Marketing Module", 1999, null));
    upsert(addon("Biometric Attendance", 1999, null));
    upsert(addon("Salary Module", 1499, null));
    upsert(addon("Accounting", 999, null));
  }

  private Plan addon(String name, int arcPrice, String perUnitLabel) {
    Plan plan = new Plan();
    plan.setPlanName(name);
    plan.setArcPrice(BigDecimal.valueOf(arcPrice));
    plan.setPrice(BigDecimal.ZERO);
    plan.setKind(PlanKind.ADDON);
    plan.setBillingPeriod(PlanBillingPeriod.YEAR);
    plan.setPerUnitLabel(perUnitLabel);
    return plan;
  }

  /** Charged at the point of use, so it carries no headline price. */
  private Plan usageAddon(String name) {
    Plan plan = new Plan();
    plan.setPlanName(name);
    plan.setArcPrice(BigDecimal.ZERO);
    plan.setPrice(BigDecimal.ZERO);
    plan.setKind(PlanKind.ADDON);
    plan.setBillingPeriod(PlanBillingPeriod.USAGE);
    plan.setBestFor("Charged per SMS sent");
    return plan;
  }

  private void seedOcrTopups() {
    upsert(ocrTopup("OCR 500 Invoices", 199, 500));
    upsert(ocrTopup("OCR 1500 Invoices", 499, 1500));
    upsert(ocrTopup("OCR 5000 Invoices", 1499, 5000));
  }

  private Plan ocrTopup(String name, int price, int invoices) {
    Plan plan = new Plan();
    plan.setPlanName(name);
    plan.setArcPrice(BigDecimal.valueOf(price));
    plan.setPrice(BigDecimal.ZERO);
    plan.setKind(PlanKind.OCR_TOPUP);
    plan.setOcrTopupInvoices(invoices);
    plan.setBillingPeriod(PlanBillingPeriod.YEAR);
    plan.setBestFor(invoices + " OCR invoices, one-off");
    return plan;
  }
}
