package com.inventory.product.search;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.pluginengine.schema.VerticalEntitySchema;
import com.inventory.pluginengine.schema.VerticalEntitySearchConfig;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.pluginengine.schema.VerticalSchemaField;
import com.inventory.pluginengine.schema.VerticalSearchSortField;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.labels.VerticalValueTypeMapper;
import com.inventory.product.search.SearchFieldCatalogService.ShopSearchContext;
import com.inventory.product.service.vertical.SchemaLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared fixtures: a pharmacy-like shop context and a sports-like one. */
final class SearchTestFixtures {

  static final String SHOP_ID = "shop-search";

  private SearchTestFixtures() {}

  /** Medical: batchNo + expiryDate in the extension, default sort expiry asc. */
  static ShopSearchContext pharmacy() {
    VerticalSchema schema =
        schema(
            List.of(
                field("name", null, "Name", "string", "core", false, false, null),
                field("batchNo", null, "Batch Number", "string", "extension", true, false, null),
                field("expiryDate", null, "Expiry Date", "date", "extension", true, true, null),
                field("companyName", null, "Company", "string", "core", false, false, null)),
            "expiryDate",
            "asc");
    return context("medical", schema);
  }

  /** Sports: brand (text) and sport (enum) searchable, default sort brand asc. */
  static ShopSearchContext sports() {
    VerticalSchema schema =
        schema(
            List.of(
                field("name", null, "Name", "string", "core", false, false, null),
                field("sport", null, "Sport", "enum", "extension", true, true,
                    List.of("cricket", "football", "gym", "tennis", "badminton", "other")),
                field("brand", null, "Brand", "string", "extension", true, true, null),
                field("model", null, "Model", "string", "extension", false, false, null),
                field("warrantyMonths", null, "Warranty", "number", "extension", false, false, null)),
            "brand",
            "asc");
    return context("sports", schema);
  }

  /** No vertical at all. */
  static ShopSearchContext plain() {
    return context(null, null);
  }

  static ShopSearchContext context(String verticalId, VerticalSchema schema) {
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setShopType(ShopType.RETAILER);
    shop.setVerticalId(verticalId);
    shop.setPluginVersion(verticalId == null ? null : "1.0.0");

    ShopRepository shops = mock(ShopRepository.class);
    when(shops.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    PricingRepository pricing = mock(PricingRepository.class);
    when(pricing.findDistinctRateNamesByShopId(anyString())).thenReturn(List.of());
    SchemaLoader loader = mock(SchemaLoader.class);
    when(loader.load(anyString(), any())).thenReturn(schema);

    LabelFieldCatalogService catalog =
        new LabelFieldCatalogService(shops, pricing, loader, new VerticalValueTypeMapper());
    return new SearchFieldCatalogService(shops, catalog, loader).context(shop);
  }

  static VerticalSchema schema(List<VerticalSchemaField> fields, String sortField, String dir) {
    VerticalEntitySchema inventory = new VerticalEntitySchema();
    inventory.setFields(fields);
    if (sortField != null) {
      VerticalSearchSortField sort = new VerticalSearchSortField();
      sort.setField(sortField);
      sort.setDirection(dir);
      VerticalEntitySearchConfig cfg = new VerticalEntitySearchConfig();
      cfg.setDefaultSort(List.of(sort));
      inventory.setSearch(cfg);
    }
    VerticalSchema schema = new VerticalSchema();
    schema.setEntities(Map.of("inventory", inventory));
    return schema;
  }

  static VerticalSchemaField field(
      String key, String apiKey, String label, String type, String storage,
      boolean searchable, boolean sortable, List<String> values) {
    VerticalSchemaField f = new VerticalSchemaField();
    f.setKey(key);
    f.setApiKey(apiKey);
    f.setLabel(label);
    f.setType(type);
    f.setStorage(storage);
    f.setSearchable(searchable);
    f.setSortable(sortable);
    f.setValues(values);
    return f;
  }
}
