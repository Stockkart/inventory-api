package com.inventory.product.migration;

import com.inventory.pluginengine.InventoryExtensionRepository;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSchemaStorage;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.service.vertical.SchemaLoader;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * M4 segregation: copy legacy core vertical fields into {@code inventory_ext_<verticalId>} for
 * existing rows. Idempotent — skips shops/inventories that already have extension documents.
 */
@Component
@Slf4j
public class SegregateVerticalInventoryExtensionMigration {

  private static final String MIGRATION_KEY = "vertical_inventory_extension_segregation_v1";
  private static final String MIGRATIONS = "app_migrations";

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private ShopRepository shopRepository;
  @Autowired private InventoryRepository inventoryRepository;
  @Autowired private PluginRegistry pluginRegistry;
  @Autowired private SchemaLoader schemaLoader;

  @EventListener(ApplicationReadyEvent.class)
  @Order(25)
  public void runAfterStartup() {
    if (Boolean.parseBoolean(
        System.getenv().getOrDefault("SKIP_INVENTORY_MIGRATIONS", "false"))) {
      log.info("Skipping {} (SKIP_INVENTORY_MIGRATIONS=true)", MIGRATION_KEY);
      return;
    }
    if (mongoTemplate.exists(Query.query(Criteria.where("_id").is(MIGRATION_KEY)), MIGRATIONS)) {
      return;
    }

    boolean dryRun =
        Boolean.parseBoolean(System.getenv().getOrDefault("MIGRATION_DRY_RUN", "false"));
    String singleShopId = System.getenv("MIGRATION_SHOP_ID");

    int shopsProcessed = 0;
    int inserted = 0;
    int skippedExisting = 0;
    int skippedEmpty = 0;

    try {
      for (Shop shop : shopRepository.findAll()) {
        if (!StringUtils.hasText(shop.getVerticalId())) {
          continue;
        }
        if (StringUtils.hasText(singleShopId) && !singleShopId.equals(shop.getShopId())) {
          continue;
        }

        InventoryExtensionRepository repository =
            pluginRegistry
                .find(shop.getVerticalId())
                .flatMap(p -> p.getInventoryExtensionRepository())
                .orElse(null);
        if (repository == null) {
          continue;
        }

        VerticalSchema schema = schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
        List<VerticalSchemaField> extensionFields =
            VerticalSchemaStorage.extensionFields(VerticalSchemaStorage.inventoryFields(schema));
        if (extensionFields.isEmpty()) {
          continue;
        }

        shopsProcessed++;
        List<Inventory> inventories = inventoryRepository.findByShopId(shop.getShopId());
        for (Inventory inventory : inventories) {
          if (!StringUtils.hasText(inventory.getId())) {
            continue;
          }
          if (repository.findByInventoryId(shop.getShopId(), inventory.getId()).isPresent()) {
            skippedExisting++;
            continue;
          }
          Map<String, Object> legacy =
              VerticalSchemaStorage.extractLegacyCoreFields(inventory, extensionFields);
          if (legacy.isEmpty()) {
            skippedEmpty++;
            continue;
          }
          if (!dryRun) {
            repository.upsert(shop.getShopId(), inventory.getId(), legacy);
          }
          inserted++;
        }
      }

      if (!dryRun) {
        mongoTemplate.save(
            new Document("_id", MIGRATION_KEY).append("appliedAt", Instant.now()), MIGRATIONS);
      }

      log.info(
          "{} completed (dryRun={}): shops={}, inserted={}, skippedExisting={}, skippedEmpty={}",
          MIGRATION_KEY,
          dryRun,
          shopsProcessed,
          inserted,
          skippedExisting,
          skippedEmpty);
    } catch (Exception e) {
      log.error(
          "Migration {} failed — fix data and remove lock document if needed", MIGRATION_KEY, e);
    }
  }
}
