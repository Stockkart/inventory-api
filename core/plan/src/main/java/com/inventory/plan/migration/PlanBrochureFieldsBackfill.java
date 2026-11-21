package com.inventory.plan.migration;

import com.inventory.plan.domain.model.Plan;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Copies the brochure limits ({@code roleLimit}, {@code attendanceUserLimit}) from
 * {@code plan-catalogue.json} onto catalogue rows seeded before those fields existed. The seeder
 * is insert-only, so without this they never reach an environment seeded earlier.
 *
 * <p>Idempotent and conservative: a field is written only where the row has never had it (field
 * absent); a value an admin set, or cleared to null, is left alone. Plan features are not touched:
 * the display-only SHOP_BRANDING and PRIORITY_SUPPORT are ticked in catalogue admin. Off unless
 * {@code stockkart.plan-brochure-backfill.enabled=true}; {@code dry-run} logs the counts only.
 */
@Component
@Slf4j
public class PlanBrochureFieldsBackfill {

  static final List<String> FIELDS = List.of("roleLimit", "attendanceUserLimit");

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private PlanCatalogueSeeder seeder;

  @Value("${stockkart.plan-brochure-backfill.enabled:false}")
  boolean enabled;

  @Value("${stockkart.plan-brochure-backfill.dry-run:false}")
  boolean dryRun;

  @EventListener(ApplicationReadyEvent.class)
  @Order(28)
  public void runOnStartup() {
    if (!enabled) {
      return;
    }
    run();
  }

  /** Returns how many (row, field) values were (or, in dry run, would be) written. */
  public int run() {
    int written = 0;
    for (Plan seed : seeder.loadSeeds()) {
      for (String field : FIELDS) {
        Object value = "roleLimit".equals(field) ? seed.getRoleLimit() : seed.getAttendanceUserLimit();
        if (value == null) {
          continue;
        }
        Query missing = Query.query(Criteria.where("code").is(seed.getCode()).and(field).exists(false));
        if (dryRun) {
          written += (int) mongoTemplate.count(missing, Plan.class);
        } else {
          written += (int) mongoTemplate.updateFirst(missing, new Update().set(field, value), Plan.class)
              .getModifiedCount();
        }
      }
    }
    log.info("[plan-brochure-backfill] {} {} brochure field value(s) on catalogue plans",
        dryRun ? "would write" : "wrote", written);
    return written;
  }
}
