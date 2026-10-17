package com.inventory.product.gridlayout;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.GridLayoutDocument;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.GridLayoutRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest;
import com.inventory.product.rest.dto.response.GridLayoutResponse;
import com.inventory.product.service.vertical.SchemaLoader;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Shop-level grid column layouts: read (saved or default), save (upsert) and reset (delete).
 *
 * <p>Mirrors {@code CardLayoutService}: {@code (shopId, userId)} overloads check membership and are
 * what the controller calls; {@code (shopId)} overloads skip the check for tests. A layout is shared
 * by every member of the shop.
 */
@Service
public class GridLayoutService {

  private final GridLayoutRepository repository;
  private final ShopRepository shopRepository;
  private final SchemaLoader schemaLoader;
  private final GridColumnCatalog columnCatalog;
  private final GridLayoutValidator validator;
  private final GridLayoutResolver resolver;
  private final UserShopMembershipService membershipService;
  private final ShopValidator shopValidator;

  public GridLayoutService(
      GridLayoutRepository repository,
      ShopRepository shopRepository,
      SchemaLoader schemaLoader,
      GridColumnCatalog columnCatalog,
      GridLayoutValidator validator,
      GridLayoutResolver resolver,
      UserShopMembershipService membershipService,
      ShopValidator shopValidator) {
    this.repository = repository;
    this.shopRepository = shopRepository;
    this.schemaLoader = schemaLoader;
    this.columnCatalog = columnCatalog;
    this.validator = validator;
    this.resolver = resolver;
    this.membershipService = membershipService;
    this.shopValidator = shopValidator;
  }

  // ---- membership-checked overloads (controller entry points) --------------------------------

  public GridLayoutResponse get(String shopId, String userId, String gridId) {
    checkAccess(shopId, userId);
    return get(shopId, gridId);
  }

  /**
   * Validates and upserts one grid's layout. Nothing is written when validation fails.
   *
   * @throws ValidationException for an unknown grid or an invalid layout
   * @throws ResourceNotFoundException when the shop does not exist
   */
  public GridLayoutResponse save(
      String shopId, String userId, String gridId, SaveGridLayoutRequest request) {
    checkAccess(shopId, userId);
    List<GridColumnDefinition> catalog = catalog(shopId, gridId);
    List<GridColumnState> columns = validator.validate(request, catalog);

    GridLayoutDocument doc =
        repository.findByShopIdAndGridId(shopId, gridId).orElseGet(GridLayoutDocument::new);
    doc.setShopId(shopId);
    doc.setGridId(gridId);
    doc.setColumns(
        columns.stream()
            .map(c -> new GridLayoutDocument.ColumnDoc(c.key(), c.widthPx(), c.hidden()))
            .toList());
    doc.setUpdatedAt(Instant.now());
    doc.setUpdatedByUserId(userId);
    GridLayoutDocument saved = repository.save(doc);
    return responseFor(gridId, Optional.of(saved), catalog);
  }

  /** Drops the saved layout so the shop is back on the default. */
  public GridLayoutResponse reset(String shopId, String userId, String gridId) {
    checkAccess(shopId, userId);
    List<GridColumnDefinition> catalog = catalog(shopId, gridId);
    repository.deleteByShopIdAndGridId(shopId, gridId);
    return responseFor(gridId, Optional.empty(), catalog);
  }

  // ---- unchecked operations (tests) ----------------------------------------------------------

  /** One grid, saved or default. Never persists. */
  public GridLayoutResponse get(String shopId, String gridId) {
    List<GridColumnDefinition> catalog = catalog(shopId, gridId);
    return responseFor(gridId, repository.findByShopIdAndGridId(shopId, gridId), catalog);
  }

  // ---- internals -----------------------------------------------------------------------------

  private List<GridColumnDefinition> catalog(String shopId, String gridId) {
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    VerticalSchema schema =
        StringUtils.hasText(shop.getVerticalId())
            ? schemaLoader.load(shop.getVerticalId(), shop.getPluginVersion())
            : null;
    return columnCatalog.columnsFor(gridId, schema);
  }

  private GridLayoutResponse responseFor(
      String gridId, Optional<GridLayoutDocument> saved, List<GridColumnDefinition> catalog) {
    if (saved.isEmpty()) {
      return new GridLayoutResponse(gridId, true, null, null, resolver.defaults(catalog), catalog);
    }
    GridLayoutDocument doc = saved.get();
    List<GridColumnState> stored =
        doc.getColumns() == null
            ? List.of()
            : doc.getColumns().stream()
                .filter(c -> c != null && c.getKey() != null)
                .map(
                    c ->
                        new GridColumnState(
                            c.getKey(),
                            c.getWidthPx() == null ? 0 : c.getWidthPx(),
                            Boolean.TRUE.equals(c.getHidden())))
                .toList();
    return new GridLayoutResponse(
        gridId,
        false,
        doc.getUpdatedAt(),
        doc.getUpdatedByUserId(),
        resolver.resolve(stored, catalog),
        catalog);
  }

  private void checkAccess(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
  }
}
