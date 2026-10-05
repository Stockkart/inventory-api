package com.inventory.product.search;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.labels.LabelFieldKeys;
import com.inventory.product.rest.dto.response.SearchFieldCatalogResponse;
import com.inventory.product.service.vertical.SchemaLoader;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The search view of the shared Field_Catalog plus the shop's default sort
 * (advanced-product-search R1.1, R1.3, R4.4).
 *
 * <p>Nothing here is search-specific knowledge about fields — that lives on each field's {@link
 * SearchSpec} in the catalog. This service only picks the shop's default sort: the vertical schema's
 * first {@code search.defaultSort} key when it names a sortable catalog field, else newest first.
 */
@Service
@Slf4j
public class SearchFieldCatalogService {

  /** Used when the shop's schema does not name a usable default sort. */
  public static final String FALLBACK_SORT = LabelFieldKeys.RECEIVED_DATE + ":desc";

  private final ShopRepository shopRepository;
  private final LabelFieldCatalogService catalogService;
  private final SchemaLoader schemaLoader;

  public SearchFieldCatalogService(
      ShopRepository shopRepository, LabelFieldCatalogService catalogService, SchemaLoader schemaLoader) {
    this.shopRepository = shopRepository;
    this.catalogService = catalogService;
    this.schemaLoader = schemaLoader;
  }

  /** Everything the engine and the UI need about a shop's search fields, loaded once. */
  public record ShopSearchContext(Shop shop, FieldCatalog catalog, String defaultSort) {

    public Optional<SearchSpec> spec(String fieldKey) {
      return catalog.findForUsage(fieldKey, com.inventory.product.labels.FieldUsage.SEARCH).map(f -> f.searchSpec());
    }
  }

  public ShopSearchContext context(String shopId) {
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    return context(shop);
  }

  public ShopSearchContext context(Shop shop) {
    FieldCatalog catalog = catalogService.catalog(shop);
    return new ShopSearchContext(shop, catalog, defaultSort(shop, catalog));
  }

  public SearchFieldCatalogResponse fields(String shopId) {
    ShopSearchContext ctx = context(shopId);
    return SearchFieldCatalogResponse.from(ctx.catalog(), ctx.defaultSort());
  }

  private String defaultSort(Shop shop, FieldCatalog catalog) {
    if (!StringUtils.hasText(shop.getVerticalId())) {
      return FALLBACK_SORT;
    }
    try {
      VerticalSchema schema = schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion());
      return LabelFieldCatalogService.schemaDefaultSort(schema, catalog).orElse(FALLBACK_SORT);
    } catch (RuntimeException e) {
      log.warn("Could not read default sort for shop {}: {}", shop.getShopId(), e.getMessage());
      return FALLBACK_SORT;
    }
  }
}
