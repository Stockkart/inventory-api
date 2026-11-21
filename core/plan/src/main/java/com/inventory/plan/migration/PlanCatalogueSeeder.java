package com.inventory.plan.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.utils.constants.PlanCatalogueConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Seeds the Starter / Professional / Enterprise catalogue from {@code classpath:plan-catalogue.json}.
 *
 * <p>Insert-only: a row that already exists for a code is never overwritten, so later admin edits
 * survive restarts. Off unless {@code plan.catalogue.seed-enabled=true}, because seeding changes
 * the live pricing page.
 */
@Component
@Slf4j
public class PlanCatalogueSeeder {

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private ResourceLoader resourceLoader;

  @Value("${plan.catalogue.seed-enabled:false}")
  private boolean seedEnabled;

  @Value("${plan.catalogue.deactivate-legacy:false}")
  private boolean deactivateLegacy;

  @EventListener(ApplicationReadyEvent.class)
  @Order(20)
  public void seedOnStartup() {
    boolean indexed = ensureCodeIndex();
    if (!seedEnabled) {
      log.info("Plan catalogue seeding disabled (plan.catalogue.seed-enabled=false)");
      return;
    }
    if (!indexed) {
      log.error("Plan catalogue not seeded: the unique code index on plans is missing");
      return;
    }
    seed(loadSeeds());
    if (deactivateLegacy) {
      deactivateLegacyPlans();
    }
  }

  /**
   * Auto index creation is off in this application, so the unique code index is created here, on
   * every startup and whether or not seeding is on: plans are also created through the admin API,
   * which relies on it to reject a second plan with the same code. If plans already share a code
   * the index is not created; the codes are logged for an operator to fix, and startup goes on.
   *
   * @return whether the index exists
   */
  boolean ensureCodeIndex() {
    try {
      mongoTemplate.indexOps(Plan.class)
          .ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().sparse());
      return true;
    } catch (RuntimeException e) {
      log.error("Could not ensure the unique code index on plans (duplicate plan codes?): {}", e.getMessage());
      return false;
    }
  }

  List<Plan> loadSeeds() {
    try (InputStream in = resourceLoader.getResource(PlanCatalogueConstants.SEED_RESOURCE).getInputStream()) {
      return objectMapper.readValue(in, new TypeReference<List<Plan>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + PlanCatalogueConstants.SEED_RESOURCE, e);
    }
  }

  /**
   * Inserts missing tiers, then links each tier inserted by this run to the next by display order
   * for upsell. Existing rows are never written: an admin who cleared or changed a link keeps it.
   */
  public List<Plan> seed(List<Plan> seeds) {
    validate(seeds);
    List<Plan> tiers = new ArrayList<>();
    java.util.Set<String> inserted = new java.util.HashSet<>();
    seeds.stream()
        .sorted(Comparator.comparing(Plan::getDisplayOrder))
        .forEach(seed -> {
          boolean existed = planRepository.findByCode(seed.getCode()).isPresent();
          Plan tier = insertIfMissing(seed);
          if (!existed) {
            inserted.add(tier.getId());
          }
          tiers.add(tier);
        });

    for (int i = 0; i < tiers.size() - 1; i++) {
      Plan tier = tiers.get(i);
      if (inserted.contains(tier.getId()) && tier.getLinkedId() == null) {
        String next = tiers.get(i + 1).getId();
        // only the link field, and only while still unset: never a whole-document save
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(tier.getId()).and("linkedId").is(null)),
            new Update().set("linkedId", next), Plan.class);
        tier.setLinkedId(next);
      }
    }
    return tiers;
  }

  /**
   * Hides pre-catalogue plans from the pricing page. Rows are kept so existing shops and past
   * transactions still resolve them.
   */
  public int deactivateLegacyPlans() {
    int count = 0;
    for (Plan legacy : planRepository.findByCodeIsNull()) {
      if (!Boolean.FALSE.equals(legacy.getActive())) {
        legacy.setActive(false);
        planRepository.save(legacy);
        count++;
      }
    }
    log.info("Deactivated {} legacy plan(s)", count);
    return count;
  }

  private Plan insertIfMissing(Plan seed) {
    return planRepository.findByCode(seed.getCode()).orElseGet(() -> {
      seed.setId(null);
      seed.setLinkedId(null);
      seed.setActive(true);
      try {
        Plan saved = planRepository.save(seed);
        log.info("Seeded plan {} ({})", saved.getCode(), saved.getId());
        return saved;
      } catch (DuplicateKeyException e) {
        return planRepository.findByCode(seed.getCode()).orElseThrow(() -> e);
      }
    });
  }

  private static void validate(List<Plan> seeds) {
    Set<String> codes = new HashSet<>();
    for (Plan seed : seeds) {
      if (!StringUtils.hasText(seed.getCode()) || !StringUtils.hasText(seed.getPlanName())) {
        throw new IllegalStateException("Plan seed is missing code or planName");
      }
      if (!codes.add(seed.getCode())) {
        throw new IllegalStateException("Duplicate plan seed code " + seed.getCode());
      }
      if (seed.getDisplayOrder() == null) {
        throw new IllegalStateException("Plan seed " + seed.getCode() + " has no displayOrder");
      }
      if (seed.getArcPrice() == null || seed.getArcPrice().compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalStateException("Plan seed " + seed.getCode() + " must have a positive arcPrice");
      }
    }
  }
}
