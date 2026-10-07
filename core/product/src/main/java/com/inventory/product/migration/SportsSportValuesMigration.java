package com.inventory.product.migration;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.result.UpdateResult;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * The sports schema's {@code sport} values changed from six lowercase words to a longer list in
 * Title Case ({@code seeds/sports-v1.json}). Enum values are compared exactly, so stock saved with
 * the old spelling ("cricket") would no longer pass validation on edit, and would not be counted
 * under "Cricket" in search. This renames the stored values once; it is idempotent (a second run
 * finds nothing to rename) and only touches the six old spellings.
 *
 * <p>Disable with {@code stockkart.sports.sport-values-migration.enabled=false}.
 */
@Component
@Slf4j
public class SportsSportValuesMigration {

  static final String COLLECTION = "inventory_ext_sports";
  static final String FIELD = "sport";

  /** Old stored value → value in the current schema. */
  static final Map<String, String> RENAMES =
      Map.of(
          "cricket", "Cricket",
          "football", "Football",
          "gym", "Gym",
          "tennis", "Tennis",
          "badminton", "Badminton",
          "other", "Other");

  @Autowired private MongoTemplate mongoTemplate;

  @Value("${stockkart.sports.sport-values-migration.enabled:true}")
  private boolean enabled;

  @EventListener(ApplicationReadyEvent.class)
  @Order(11) // after VerticalSchemaSeeder (10) has written the new schema
  public void run() {
    if (!enabled) {
      log.info("[sports-values] disabled by configuration");
      return;
    }
    if (!mongoTemplate.collectionExists(COLLECTION)) {
      return;
    }
    MongoCollection<Document> col = mongoTemplate.getCollection(COLLECTION);
    long total = 0;
    for (Map.Entry<String, String> e : RENAMES.entrySet()) {
      try {
        UpdateResult r =
            col.updateMany(new Document(FIELD, e.getKey()), new Document("$set", new Document(FIELD, e.getValue())));
        if (r.getModifiedCount() > 0) {
          log.info("[sports-values] {} → {}: {} lots", e.getKey(), e.getValue(), r.getModifiedCount());
          total += r.getModifiedCount();
        }
      } catch (RuntimeException ex) {
        log.error("[sports-values] failed to rename {} → {}: {}", e.getKey(), e.getValue(), ex.getMessage(), ex);
      }
    }
    if (total > 0) {
      log.info("[sports-values] renamed the sport on {} lots to the current schema spelling", total);
    }
  }
}
