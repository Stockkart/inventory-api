package com.inventory.product.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Runs only with {@code -Dstockkart.test.mongo.uri=…} (see {@code InventorySearchEngineIT} for the
 * one-line Docker command).
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class SportsSportValuesMigrationIT {

  private static MongoClient client;
  private static MongoTemplate template;

  @BeforeAll
  static void connect() {
    String uri = System.getProperty("stockkart.test.mongo.uri");
    client = MongoClients.create(uri);
    template = new MongoTemplate(client, uri.substring(uri.lastIndexOf('/') + 1).split("\\?")[0] + "_sports");
    template.getDb().drop();
  }

  @AfterAll
  static void close() {
    if (template != null) template.getDb().drop();
    if (client != null) client.close();
  }

  @Test
  void renamesOnlyTheOldSpellingsAndIsIdempotent() {
    template.getCollection(SportsSportValuesMigration.COLLECTION)
        .insertMany(
            List.of(
                new Document("inventoryId", "a").append("sport", "cricket"),
                new Document("inventoryId", "b").append("sport", "gym"),
                new Document("inventoryId", "c").append("sport", "Cricket"),
                new Document("inventoryId", "d").append("sport", "Basketball"),
                new Document("inventoryId", "e").append("sport", "rugby")));

    SportsSportValuesMigration migration = new SportsSportValuesMigration();
    ReflectionTestUtils.setField(migration, "mongoTemplate", template);
    ReflectionTestUtils.setField(migration, "enabled", true);
    migration.run();
    migration.run(); // second run finds nothing to do

    List<String> sports =
        template.getCollection(SportsSportValuesMigration.COLLECTION).find().sort(new Document("inventoryId", 1))
            .map(d -> d.getString("sport")).into(new java.util.ArrayList<>());
    assertThat(sports).containsExactly("Cricket", "Gym", "Cricket", "Basketball", "rugby");
  }
}
