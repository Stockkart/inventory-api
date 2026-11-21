package com.inventory.plan.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.utils.constants.PlanCatalogueConstants;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates the add-on indexes on every startup and, when {@code plan.addons.seed-enabled=true},
 * inserts missing add-ons from {@code classpath:addon-catalogue.json}. Insert-only, so admin edits
 * survive restarts.
 */
@Component
@Slf4j
public class AddOnCatalogueSeeder {

  @Autowired
  private AddOnRepository addOnRepository;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private ResourceLoader resourceLoader;

  @Value("${plan.addons.seed-enabled:false}")
  private boolean seedEnabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(21)
  public void seedOnStartup() {
    ensureIndexes();
    if (!seedEnabled) {
      log.info("Add-on catalogue seeding disabled (plan.addons.seed-enabled=false)");
      return;
    }
    seed(loadSeeds());
  }

  void ensureIndexes() {
    mongoTemplate.indexOps(AddOn.class)
        .ensureIndex(new Index().on("code", Sort.Direction.ASC).unique().named("code_unique"));
    var shopAddOns = mongoTemplate.indexOps(ShopAddOn.class);
    shopAddOns.ensureIndex(new Index()
        .on("sourceOrderId", Sort.Direction.ASC)
        .on("addOnCode", Sort.Direction.ASC)
        .unique()
        .partial(PartialIndexFilter.of(Criteria.where("sourceOrderId").exists(true)))
        .named("source_order_addon_unique"));
    shopAddOns.ensureIndex(new Index()
        .on("shopId", Sort.Direction.ASC)
        .on("grantType", Sort.Direction.ASC)
        .named("shop_grantType"));
  }

  List<AddOn> loadSeeds() {
    try (InputStream in = resourceLoader.getResource(PlanCatalogueConstants.ADDON_SEED_RESOURCE).getInputStream()) {
      return objectMapper.readValue(in, new TypeReference<List<AddOn>>() {});
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + PlanCatalogueConstants.ADDON_SEED_RESOURCE, e);
    }
  }

  public int seed(List<AddOn> seeds) {
    validate(seeds);
    int inserted = 0;
    for (AddOn seed : seeds) {
      if (addOnRepository.findByCode(seed.getCode()).isPresent()) {
        continue;
      }
      Instant now = Instant.now();
      seed.setId(null);
      seed.setActive(true);
      seed.setCreatedAt(now);
      seed.setUpdatedAt(now);
      try {
        mongoTemplate.insert(seed);
        inserted++;
        log.info("Seeded add-on {}", seed.getCode());
      } catch (DuplicateKeyException e) {
        log.info("Add-on {} was seeded concurrently", seed.getCode());
      }
    }
    return inserted;
  }

  private static void validate(List<AddOn> seeds) {
    Set<String> codes = new HashSet<>();
    for (AddOn seed : seeds) {
      if (!StringUtils.hasText(seed.getCode()) || !StringUtils.hasText(seed.getName())) {
        throw new IllegalStateException("Add-on seed is missing code or name");
      }
      if (!codes.add(seed.getCode())) {
        throw new IllegalStateException("Duplicate add-on seed code " + seed.getCode());
      }
      if (seed.getPrice() == null || seed.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalStateException("Add-on seed " + seed.getCode() + " must have a positive price");
      }
      if (seed.getGrantType() == null || seed.getBillingType() == null) {
        throw new IllegalStateException("Add-on seed " + seed.getCode() + " needs grantType and billingType");
      }
      if (seed.getGrantType() == AddOnGrantType.FEATURE && seed.getGrantsFeature() == null) {
        throw new IllegalStateException("Feature add-on seed " + seed.getCode() + " needs grantsFeature");
      }
    }
  }
}
