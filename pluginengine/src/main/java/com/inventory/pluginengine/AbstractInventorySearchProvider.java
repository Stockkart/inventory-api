package com.inventory.pluginengine;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Optional base for vertical {@link InventorySearchProvider} beans. Holds wiring only — no search
 * algorithm. Implement {@link #search(String, InventorySearchQuery)} in the plugin module.
 */
public abstract class AbstractInventorySearchProvider implements InventorySearchProvider {

  private final MongoTemplate mongoTemplate;
  private final Class<?> extensionDocumentClass;

  protected AbstractInventorySearchProvider(
      MongoTemplate mongoTemplate, Class<?> extensionDocumentClass) {
    this.mongoTemplate = mongoTemplate;
    this.extensionDocumentClass = extensionDocumentClass;
  }

  protected MongoTemplate mongoTemplate() {
    return mongoTemplate;
  }

  protected Class<?> extensionDocumentClass() {
    return extensionDocumentClass;
  }

  @Override
  public abstract InventorySearchResult search(String shopId, InventorySearchQuery query);
}
