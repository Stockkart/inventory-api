package com.inventory.product.cardlayout;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardSurfaceDefinition;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.product.domain.model.CardLayoutDocument;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.CardLayoutRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest;
import com.inventory.product.rest.dto.response.CardFieldCatalogResponse;
import com.inventory.product.rest.dto.response.CardLayoutsResponse;
import com.inventory.product.rest.dto.response.SurfaceLayoutResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Shop-level product card layouts: read, save (upsert), defaults and the card field catalog
 * (configurable-product-card Req 1.5, 2.4, 2.5, 4.1–4.8, 10.2).
 *
 * <p>Mirrors {@code LabelLayoutService}: {@code (shopId, userId)} overloads check membership and are
 * what controllers call; {@code (shopId)} overloads skip the check for already shop-scoped callers
 * and tests. Only {@link #save} writes. Every read builds the Field_Catalog once and, for {@link
 * #getAll}, runs one repository query for the whole shop.
 */
@Service
public class CardLayoutService {

  private final CardLayoutRepository repository;
  private final ShopRepository shopRepository;
  private final LabelFieldCatalogService catalogService;
  private final CardSurfaceRegistry surfaceRegistry;
  private final CardLayoutValidator validator;
  private final CardLayoutResolver resolver;
  private final UserShopMembershipService membershipService;
  private final ShopValidator shopValidator;

  public CardLayoutService(
      CardLayoutRepository repository,
      ShopRepository shopRepository,
      LabelFieldCatalogService catalogService,
      CardSurfaceRegistry surfaceRegistry,
      CardLayoutValidator validator,
      CardLayoutResolver resolver,
      UserShopMembershipService membershipService,
      ShopValidator shopValidator) {
    this.repository = repository;
    this.shopRepository = shopRepository;
    this.catalogService = catalogService;
    this.surfaceRegistry = surfaceRegistry;
    this.validator = validator;
    this.resolver = resolver;
    this.membershipService = membershipService;
    this.shopValidator = shopValidator;
  }

  // ---- membership-checked overloads (controller entry points) --------------------------------

  public CardLayoutsResponse getAll(String shopId, String userId) {
    checkAccess(shopId, userId);
    return getAll(shopId);
  }

  public SurfaceLayoutResponse get(String shopId, String userId, String surfaceId) {
    checkAccess(shopId, userId);
    return get(shopId, surfaceId);
  }

  public SurfaceLayoutResponse defaults(String shopId, String userId, String surfaceId) {
    checkAccess(shopId, userId);
    return defaults(shopId, surfaceId);
  }

  public CardFieldCatalogResponse fieldCatalog(String shopId, String userId) {
    checkAccess(shopId, userId);
    return fieldCatalog(shopId);
  }

  /**
   * Validates and upserts one surface's layouts (Req 3.8, 4.1, 4.6, 4.7).
   *
   * <p>Order: membership → shop + catalog → surface lookup → validation (fills omitted variants with
   * defaults) → upsert by (shopId, surfaceId) → resolve. Nothing is written when validation fails.
   *
   * @throws ValidationException for an unknown surface or an invalid layout
   * @throws ResourceNotFoundException when the shop does not exist
   */
  public SurfaceLayoutResponse save(
      String shopId, String userId, String surfaceId, SaveCardLayoutRequest request) {
    checkAccess(shopId, userId);
    Context ctx = context(shopId);
    CardSurfaceDefinition surface = ctx.requireSurface(surfaceId);

    Function<CardVariant, CardLayout> defaults =
        v -> surfaceRegistry.defaultLayout(surface, ctx.verticalId(), v);
    Map<CardVariant, CardLayout> layouts = validator.validate(request, surface, ctx.catalog(), defaults);

    CardLayoutDocument doc =
        repository.findByShopIdAndSurfaceId(shopId, surfaceId).orElseGet(CardLayoutDocument::new);
    doc.setShopId(shopId);
    doc.setSurfaceId(surfaceId);
    doc.setVariants(CardLayoutDocuments.fromLayouts(layouts));
    doc.setUpdatedAt(Instant.now());
    doc.setUpdatedByUserId(userId);
    CardLayoutDocument saved = repository.save(doc);

    return SurfaceLayoutResponse.of(
        surface, resolveAll(layouts, surface, ctx.catalog()), false, saved.getUpdatedAt(), saved.getUpdatedByUserId());
  }

  // ---- unchecked operations (shop-scoped callers and tests) ----------------------------------

  /** Every surface available to the shop, saved or default (Req 4.4, 10.2). Never persists. */
  public CardLayoutsResponse getAll(String shopId) {
    Context ctx = context(shopId);
    Map<String, CardLayoutDocument> saved =
        repository.findByShopId(shopId).stream()
            .collect(Collectors.toMap(CardLayoutDocument::getSurfaceId, d -> d, (a, b) -> a));
    List<SurfaceLayoutResponse> surfaces = new ArrayList<>();
    for (CardSurfaceDefinition surface : ctx.surfaces()) {
      surfaces.add(responseFor(surface, Optional.ofNullable(saved.get(surface.surfaceId())), ctx));
    }
    return new CardLayoutsResponse(surfaces);
  }

  /** One surface, saved or default (Req 4.2, 4.3). Never persists. */
  public SurfaceLayoutResponse get(String shopId, String surfaceId) {
    Context ctx = context(shopId);
    CardSurfaceDefinition surface = ctx.requireSurface(surfaceId);
    return responseFor(surface, repository.findByShopIdAndSurfaceId(shopId, surfaceId), ctx);
  }

  /** The built-in layouts for the shop's vertical, resolved (Req 4.5). Never persists. */
  public SurfaceLayoutResponse defaults(String shopId, String surfaceId) {
    Context ctx = context(shopId);
    CardSurfaceDefinition surface = ctx.requireSurface(surfaceId);
    return SurfaceLayoutResponse.of(
        surface, resolveAll(defaultLayouts(surface, ctx), surface, ctx.catalog()), true, null, null);
  }

  /** The card view of the Field_Catalog plus the shop's surfaces and editor limits (Req 1.5). */
  public CardFieldCatalogResponse fieldCatalog(String shopId) {
    Context ctx = context(shopId);
    return CardFieldCatalogResponse.from(ctx.catalog(), ctx.surfaces());
  }

  // ---- internals -----------------------------------------------------------------------------

  /** Everything a request needs about the shop, loaded once. */
  private record Context(Shop shop, FieldCatalog catalog, List<CardSurfaceDefinition> surfaces) {

    String verticalId() {
      return shop.getVerticalId();
    }

    CardSurfaceDefinition requireSurface(String surfaceId) {
      return surfaces.stream()
          .filter(s -> s.surfaceId().equals(surfaceId))
          .findFirst()
          .orElseThrow(
              () ->
                  new ValidationException(
                      "Unknown card surface: "
                          + surfaceId
                          + " (available: "
                          + surfaces.stream().map(CardSurfaceDefinition::surfaceId).collect(Collectors.joining(", "))
                          + ")"));
    }
  }

  private Context context(String shopId) {
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    FieldCatalog catalog = catalogService.catalog(shop);
    return new Context(shop, catalog, surfaceRegistry.surfacesFor(shop.getVerticalId()));
  }

  private SurfaceLayoutResponse responseFor(
      CardSurfaceDefinition surface, Optional<CardLayoutDocument> saved, Context ctx) {
    if (saved.isEmpty()) {
      return SurfaceLayoutResponse.of(
          surface, resolveAll(defaultLayouts(surface, ctx), surface, ctx.catalog()), true, null, null);
    }
    CardLayoutDocument doc = saved.get();
    Map<CardVariant, CardLayout> layouts = CardLayoutDocuments.toLayouts(doc);
    // A legacy or partial document may miss a variant; serve the default for it (Req 5.3).
    for (CardVariant v : surface.variants()) {
      layouts.computeIfAbsent(v, variant -> surfaceRegistry.defaultLayout(surface, ctx.verticalId(), variant));
    }
    layouts.keySet().retainAll(surface.variants());
    return SurfaceLayoutResponse.of(
        surface, resolveAll(layouts, surface, ctx.catalog()), false, doc.getUpdatedAt(), doc.getUpdatedByUserId());
  }

  private Map<CardVariant, CardLayout> defaultLayouts(CardSurfaceDefinition surface, Context ctx) {
    Map<CardVariant, CardLayout> layouts = new EnumMap<>(CardVariant.class);
    for (CardVariant v : surface.variants()) {
      layouts.put(v, surfaceRegistry.defaultLayout(surface, ctx.verticalId(), v));
    }
    return layouts;
  }

  private Map<CardVariant, ResolvedCardLayout> resolveAll(
      Map<CardVariant, CardLayout> layouts, CardSurfaceDefinition surface, FieldCatalog catalog) {
    Map<CardVariant, ResolvedCardLayout> resolved = new EnumMap<>(CardVariant.class);
    layouts.forEach((v, l) -> resolved.put(v, resolver.resolve(l, surface, catalog)));
    return resolved;
  }

  private void checkAccess(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
  }
}
