package com.inventory.product.gridlayout;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.schema.VerticalSchema;
import com.inventory.product.domain.model.GridLayoutDocument;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.GridLayoutRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.request.GridLayoutChangeRequest;
import com.inventory.product.rest.dto.response.GridLayoutResponse;
import com.inventory.product.service.vertical.SchemaLoader;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.RbacService;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Shop-level grid column layouts: read (saved or default), change one column, and reset.
 *
 * <p>A layout is shared by every member of the shop; only an owner or admin may change it. A
 * change is applied to the latest saved layout under an optimistic lock ({@code revision}): when
 * another member saved in between, the change is re-applied on top of theirs, so changes to
 * different columns never overwrite each other.
 */
@Service
@Slf4j
public class GridLayoutService {

  /** Attempts to apply one change when other members keep saving in between. */
  static final int MAX_ATTEMPTS = 3;

  private final GridLayoutRepository repository;
  private final ShopRepository shopRepository;
  private final SchemaLoader schemaLoader;
  private final GridColumnCatalog columnCatalog;
  private final GridLayoutChangeApplier applier;
  private final GridLayoutResolver resolver;
  private final UserShopMembershipService membershipService;
  private final RbacService rbacService;
  private final ShopValidator shopValidator;

  public GridLayoutService(
      GridLayoutRepository repository,
      ShopRepository shopRepository,
      SchemaLoader schemaLoader,
      GridColumnCatalog columnCatalog,
      GridLayoutChangeApplier applier,
      GridLayoutResolver resolver,
      UserShopMembershipService membershipService,
      RbacService rbacService,
      ShopValidator shopValidator) {
    this.repository = repository;
    this.shopRepository = shopRepository;
    this.schemaLoader = schemaLoader;
    this.columnCatalog = columnCatalog;
    this.applier = applier;
    this.resolver = resolver;
    this.membershipService = membershipService;
    this.rbacService = rbacService;
    this.shopValidator = shopValidator;
  }

  /** One grid, saved or default, with whether the caller may change it. Never persists. */
  public GridLayoutResponse get(String shopId, String userId, String gridId) {
    checkAccess(shopId, userId);
    List<GridColumnDefinition> catalog = catalog(shopId, gridId);
    return responseFor(
        gridId,
        repository.findByShopIdAndGridId(shopId, gridId),
        catalog,
        rbacService.canManageShopLayouts(userId, shopId));
  }

  /**
   * Applies one column change to the latest saved layout.
   *
   * @throws ValidationException for an unknown grid or a change that breaks a column rule
   * @throws BaseException {@code ACCESS_DENIED} unless the caller is an owner or admin; {@code
   *     DUPLICATE_RESOURCE} (409) when other members kept saving through every attempt
   */
  public GridLayoutResponse change(
      String shopId, String userId, String gridId, GridLayoutChangeRequest change) {
    checkAccess(shopId, userId);
    rbacService.requireShopLayoutManagement(userId, shopId);
    List<GridColumnDefinition> catalog = catalog(shopId, gridId);

    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      Optional<GridLayoutDocument> current = repository.findByShopIdAndGridId(shopId, gridId);
      List<GridColumnState> resolved =
          current.map(d -> resolver.resolve(stored(d), catalog)).orElseGet(() -> resolver.defaults(catalog));
      List<GridColumnState> next = applier.apply(change, resolved, catalog);

      GridLayoutDocument doc = current.orElseGet(GridLayoutDocument::new);
      doc.setShopId(shopId);
      doc.setGridId(gridId);
      doc.setColumns(
          next.stream()
              .map(c -> new GridLayoutDocument.ColumnDoc(c.key(), c.widthPx(), c.hidden()))
              .toList());
      doc.setUpdatedAt(Instant.now());
      doc.setUpdatedByUserId(userId);
      try {
        GridLayoutDocument saved = repository.save(doc);
        return responseFor(gridId, Optional.of(saved), catalog, true);
      } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
        log.info(
            "Grid layout {} for shop {} changed concurrently (attempt {}), re-applying",
            gridId,
            shopId,
            attempt);
      }
    }
    throw new BaseException(
        ErrorCode.DUPLICATE_RESOURCE,
        "The column layout is being changed by someone else. Please try again.");
  }

  /** Drops the saved layout so the shop is back on the default. Owner or admin only. */
  public GridLayoutResponse reset(String shopId, String userId, String gridId) {
    checkAccess(shopId, userId);
    rbacService.requireShopLayoutManagement(userId, shopId);
    List<GridColumnDefinition> catalog = catalog(shopId, gridId);
    repository.deleteByShopIdAndGridId(shopId, gridId);
    return responseFor(gridId, Optional.empty(), catalog, true);
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

  private static List<GridColumnState> stored(GridLayoutDocument doc) {
    if (doc.getColumns() == null) {
      return List.of();
    }
    return doc.getColumns().stream()
        .filter(c -> c != null && c.getKey() != null)
        .map(
            c ->
                new GridColumnState(
                    c.getKey(),
                    c.getWidthPx() == null ? 0 : c.getWidthPx(),
                    Boolean.TRUE.equals(c.getHidden())))
        .toList();
  }

  private GridLayoutResponse responseFor(
      String gridId,
      Optional<GridLayoutDocument> saved,
      List<GridColumnDefinition> catalog,
      boolean canEdit) {
    if (saved.isEmpty()) {
      return new GridLayoutResponse(
          gridId, true, null, canEdit, null, null, resolver.defaults(catalog), catalog);
    }
    GridLayoutDocument doc = saved.get();
    return new GridLayoutResponse(
        gridId,
        false,
        doc.getRevision(),
        canEdit,
        doc.getUpdatedAt(),
        doc.getUpdatedByUserId(),
        resolver.resolve(stored(doc), catalog),
        catalog);
  }

  private void checkAccess(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
  }
}
