package com.inventory.plan.config;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds plan master data. Plans form a linked list: Base -> Standard -> Silver -> Gold -> Diamond.
 * Extra User Plan is independent (not linked to any plan).
 * <p>price = one-time service help fee (annual amount, for first-time support).
 * arcPrice = annual subscription price (what customers pay per year).
 * billingLimit, billCountLimit, smsLimit, whatsappLimit are monthly limits.</p>
 */
@Component
@Slf4j
@Profile("!test")
public class PlanDataSeeder implements CommandLineRunner {

  public static final int TRIAL_DAYS = 30;
  /** Billing limits per plan - all non-unlimited plans must have these set */
  public static final BigDecimal BASE_BILLING_LIMIT = new BigDecimal("150000");
  public static final int BASE_BILL_COUNT = 450;
  public static final BigDecimal STANDARD_BILLING_LIMIT = new BigDecimal("1500000");
  public static final int STANDARD_BILL_COUNT = 4500;
  public static final BigDecimal SILVER_BILLING_LIMIT = new BigDecimal("1500000");
  public static final int SILVER_BILL_COUNT = 4500;
  public static final BigDecimal GOLD_BILLING_LIMIT = new BigDecimal("1500000");
  public static final int GOLD_BILL_COUNT = 4500;
  public static final int EXTRA_USER_PRICE = 1500;

  @Autowired
  private PlanRepository planRepository;

  /**
   * Returns Base plan defaults for trial shops (when plan is not yet purchased).
   */
  public static Plan getBasePlanDefaults() {
    Plan p = new Plan();
    p.setPlanName("Base");
    p.setBillingLimit(BASE_BILLING_LIMIT);
    p.setBillCountLimit(BASE_BILL_COUNT);
    p.setSmsLimit(0);
    p.setWhatsappLimit(0);
    p.setUserLimit(1);
    p.setUnlimited(false);
    return p;
  }

  @Override
  public void run(String... args) {
    if (planRepository.count() == 0) {
      seedAllPlans();
    } else {
      ensureCustomizePlan();
    }
  }

  private void seedAllPlans() {
    log.info("Seeding plan master data");

    // Linked chain: Base -> Standard -> Silver -> Gold -> Diamond (Diamond has no linkedId)
    List<Plan> plans = List.of(
        createPlan("Base", "3000", "2400", BASE_BILLING_LIMIT, BASE_BILL_COUNT, 0, 0, 1, false,
            "Small businesses with limited billing and single operator"),
        createPlan("Standard", "4500", "4000", STANDARD_BILLING_LIMIT, STANDARD_BILL_COUNT, 200, 0, 2, false,
            "Shops starting promotional marketing via SMS"),
        createPlan("Silver", "4600", "4200", SILVER_BILLING_LIMIT, SILVER_BILL_COUNT, 200, 100, 2, false,
            "Shops starting WhatsApp engagement along with SMS"),
        createPlan("Gold", "4800", "4500", GOLD_BILLING_LIMIT, GOLD_BILL_COUNT, 500, 1000, 3, false,
            "Growing businesses with active customer marketing"),
        createPlan("Diamond", "5200", "5000", null, null, null, null, null, true,
            "High-volume or scaling businesses")
    );

    Plan prev = null;
    for (Plan p : plans) {
      p = planRepository.save(p);
      if (prev != null) {
        prev.setLinkedId(p.getId());
        planRepository.save(prev);
      }
      prev = p;
    }

    // Extra User Plan - independent, not linked to any plan
    Plan extraUser = createPlan("Extra User Plan", "1500", "1500", null, null, null, null, null, false,
        "Add extra users at ₹1500 per user per year");
    extraUser.setLinkedId(null);
    planRepository.save(extraUser);

    log.info("Seeded {} plans", plans.size() + 1);
  }

  /** Adds Extra User Plan if missing. Independent plan - not linked to Diamond or any other. */
  private void ensureCustomizePlan() {
    if (planRepository.findByPlanName("Extra User Plan").isPresent()) {
      log.debug("Extra User Plan already exists, skipping");
      return;
    }
    // Migrate old Customize to Extra User Plan, or add new
    var existingCustomize = planRepository.findByPlanName("Customize");
    if (existingCustomize.isPresent()) {
      Plan p = existingCustomize.get();
      p.setPlanName("Extra User Plan");
      p.setBestFor("Add extra users at ₹1500 per user per year");
      p.setBillingLimit(null);
      p.setBillCountLimit(null);
      p.setSmsLimit(0);
      p.setWhatsappLimit(0);
      p.setUserLimit(null);
      p.setLinkedId(null);
      planRepository.save(p);
      log.info("Migrated Customize to Extra User Plan");
    } else {
      Plan extraUser = createPlan("Extra User Plan", "1500", "1500", null, null, null, null, null, false,
          "Add extra users at ₹1500 per user per year");
      extraUser.setLinkedId(null);
      planRepository.save(extraUser);
      log.info("Extra User Plan added");
    }
    // Ensure Diamond has no linkedId (independent of Extra User Plan)
    planRepository.findByPlanName("Diamond").ifPresent(diamond -> {
      if (diamond.getLinkedId() != null) {
        diamond.setLinkedId(null);
        planRepository.save(diamond);
      }
    });
  }

  private Plan createPlan(String name, String price, String arc, BigDecimal billingLimit, Integer billCount,
      Integer sms, Integer whatsapp, Integer users, boolean unlimited, String bestFor) {
    Plan p = new Plan();
    p.setPlanName(name);
    p.setPrice(new BigDecimal(price));
    p.setArcPrice(new BigDecimal(arc));
    p.setBillingLimit(billingLimit);
    p.setBillCountLimit(billCount);
    p.setSmsLimit(sms);
    p.setWhatsappLimit(whatsapp);
    p.setUserLimit(users);
    p.setUnlimited(unlimited);
    p.setBestFor(bestFor);
    return p;
  }
}
