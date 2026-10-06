package com.inventory.product.migration;

import com.inventory.pluginengine.InventoryExtensionDocument;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.stereotype.Component;

/**
 * Creates the indexes advanced search relies on (advanced-product-search R4.7). Automatic index
 * creation is off in this application, so the {@code @CompoundIndex} annotations on {@link Product}
 * and {@link Inventory} are documentation; this runner is what builds them. Idempotent: {@code
 * ensureIndex} is a no-op when the index already exists. Builds run in the background on the server
 * by default, so reads and writes keep working while a large collection is indexed the first time.
 *
 * <p>Extension collections ({@code inventory_ext_<vertical>}) are covered too: every index a plugin
 * declares on its {@link InventoryExtensionDocument} with {@code @Indexed} / {@code @CompoundIndex}
 * is created here, because search joins and sorts on those fields ({@code inventoryId}, and the
 * vertical's {@code (shopId, field)} pairs such as expiry date and batch number).
 *
 * <p>Disable with {@code stockkart.search.index-migration.enabled=false}.
 */
@Component
@Slf4j
public class SearchIndexMigration {

  static final String EXTENSION_SCAN_PACKAGE = "com.inventory";

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private MongoMappingContext mappingContext;

  @Value("${stockkart.search.index-migration.enabled:true}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(6)
  public void run() {
    if (!enabled) {
      log.info("[search-index] disabled by configuration");
      return;
    }
    ensure(Product.class, "shop_company_idx", List.of("shopId", "companyName"), false);
    ensure(Product.class, "shop_hsn_idx", List.of("shopId", "hsn"), true);
    ensure(Inventory.class, "shop_product_idx", List.of("shopId", "productId"), false);
    ensure(Inventory.class, "shop_location_idx", List.of("shopId", "location"), false);
    ensure(Inventory.class, "shop_batch_idx", List.of("shopId", "batchNo"), true);
    ensure(Inventory.class, "shop_stock_idx", List.of("shopId", "currentCount"), false);
    ensureCreatedDesc();
    ensureDeclaredExtensionIndexes();
  }

  /**
   * The indexes each plugin declared on its extension document, created as declared. Extension
   * classes are found on the classpath (they live in plugin modules outside the entity-scan package),
   * so a new vertical gets its indexes without touching this class.
   */
  private void ensureDeclaredExtensionIndexes() {
    MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(InventoryExtensionDocument.class));
    for (BeanDefinition bd : scanner.findCandidateComponents(EXTENSION_SCAN_PACKAGE)) {
      Class<?> type;
      try {
        type = Class.forName(bd.getBeanClassName());
      } catch (ClassNotFoundException e) {
        continue;
      }
      if (type.isInterface() || !type.isAnnotationPresent(Document.class)) {
        continue;
      }
      MongoPersistentEntity<?> entity = mappingContext.getRequiredPersistentEntity(type);
      for (IndexDefinition def : resolver.resolveIndexFor(entity.getTypeInformation())) {
        try {
          mongoTemplate.indexOps(entity.getCollection()).ensureIndex(def);
        } catch (RuntimeException e) {
          log.error("[search-index] failed to create {} on {}: {}", def.getIndexKeys().toJson(), entity.getCollection(), e.getMessage());
        }
      }
      log.info("[search-index] ensured declared indexes on {}", entity.getCollection());
    }
  }

  private void ensure(Class<?> entity, String name, List<String> fields, boolean sparse) {
    try {
      IndexOperations ops = mongoTemplate.indexOps(entity);
      if (ops.getIndexInfo().stream().anyMatch(i -> name.equals(i.getName()))) {
        return;
      }
      Index index = new Index().named(name);
      for (String f : fields) {
        index.on(f, Sort.Direction.ASC);
      }
      if (sparse) {
        index.sparse();
      }
      ops.ensureIndex(index);
      log.info("[search-index] created {} on {}", name, entity.getSimpleName());
    } catch (RuntimeException e) {
      log.error("[search-index] failed to create {}: {}", name, e.getMessage(), e);
    }
  }

  private void ensureCreatedDesc() {
    try {
      IndexOperations ops = mongoTemplate.indexOps(Inventory.class);
      if (ops.getIndexInfo().stream().anyMatch(i -> "shop_created_idx".equals(i.getName()))) {
        return;
      }
      ops.ensureIndex(
          new Index().on("shopId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("shop_created_idx"));
      log.info("[search-index] created shop_created_idx on Inventory");
    } catch (RuntimeException e) {
      log.error("[search-index] failed to create shop_created_idx: {}", e.getMessage(), e);
    }
  }
}
