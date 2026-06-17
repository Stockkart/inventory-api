package com.inventory.pluginengine.defaultprovider;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.AbstractInventorySearchProvider;
import com.inventory.pluginengine.InventorySearchQuery;
import com.inventory.pluginengine.InventorySearchResult;
import com.inventory.pluginengine.schema.VerticalSchema;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Default schema-driven extension search. Vertical plugins extend this and override
 * {@link #applyVerticalFilters} for virtual filters (e.g. expiryBefore).
 */
public abstract class SchemaDrivenInventorySearchProvider extends AbstractInventorySearchProvider {

  protected SchemaDrivenInventorySearchProvider(
      MongoTemplate mongoTemplate, Class<?> extensionDocumentClass) {
    super(mongoTemplate, extensionDocumentClass);
  }

  @Override
  public InventorySearchResult search(String shopId, InventorySearchQuery query) {
    VerticalSchema schema = query.getSchema();
    if (schema == null) {
      throw new ValidationException("Search requires vertical schema context");
    }

    Criteria criteria = Criteria.where("shopId").is(shopId);
    SchemaExtensionSearchFilters.applySearchableEquals(criteria, query.getFilters(), schema);
    applyVerticalFilters(criteria, query, schema);
    applyRestrictIds(criteria, query.getRestrictInventoryIds());

    int limit = query.getLimit() > 0 ? Math.min(query.getLimit(), 200) : 50;
    SchemaSearchConfigResolver.ResolvedSearch resolved =
        SchemaSearchConfigResolver.resolve(schema, query.getSort());

    SchemaDrivenExtensionSearch.SearchPage page =
        SchemaDrivenExtensionSearch.searchPage(
            mongoTemplate(),
            extensionDocumentClass(),
            criteria,
            resolved,
            query.getCursor(),
            query.getSkip(),
            limit);

    return InventorySearchResult.builder()
        .inventoryIds(page.inventoryIds())
        .nextCursor(page.nextCursor())
        .totalMatched(page.inventoryIds().size())
        .build();
  }

  protected void applyVerticalFilters(
      Criteria criteria, InventorySearchQuery query, VerticalSchema schema) {}

  protected static void applyRestrictIds(Criteria criteria, Set<String> restrictInventoryIds) {
    if (restrictInventoryIds == null || restrictInventoryIds.isEmpty()) {
      return;
    }
    criteria.and(ExtensionDocumentField.inventoryIdFieldName()).in(restrictInventoryIds);
  }
}
