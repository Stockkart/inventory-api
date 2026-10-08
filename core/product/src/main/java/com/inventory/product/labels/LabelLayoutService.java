package com.inventory.product.labels;

import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.LabelLayoutRepository;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.FieldCatalogResponse;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Shop-level barcode label layout: read, save (upsert), shop-type defaults and the field catalog
 * (Req 2.1, 2.2, 2.3, 2.8, 2.9, 2.12, 3.9, 4.1–4.6, 6.11, 9.2, 9.3, 9.5).
 *
 * <p>Two flavours of every read/write operation exist:
 *
 * <ul>
 *   <li>{@code (shopId, userId)} overloads perform the membership check {@code
 *       shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId))}, mirroring
 *       {@code InvoiceSettingsService}; controllers call these.
 *   <li>{@code (shopId)} overloads skip the check; they are for callers that are already shop-scoped
 *       (the labels endpoint via {@code BarcodeService}) and for tests.
 * </ul>
 *
 * <p>Only {@link #save} persists anything. {@link #get}, {@link #shopTypeDefaults}, {@link
 * #fieldCatalog} and {@link #resolvedConfig} never write (Req 2.2, 4.3, 9.3).
 */
@Service
public class LabelLayoutService {

  private final LabelLayoutRepository repository;
  private final LabelFieldCatalogService catalogService;
  private final LabelLayoutValidator validator;
  private final UserShopMembershipService membershipService;
  private final ShopValidator shopValidator;

  public LabelLayoutService(
      LabelLayoutRepository repository,
      LabelFieldCatalogService catalogService,
      LabelLayoutValidator validator,
      UserShopMembershipService membershipService,
      ShopValidator shopValidator) {
    this.repository = repository;
    this.catalogService = catalogService;
    this.validator = validator;
    this.membershipService = membershipService;
    this.shopValidator = shopValidator;
  }

  // ---- membership-checked overloads (controller entry points) --------------------------------

  /** {@link #get(String)} after verifying {@code userId} is a member of {@code shopId}. */
  public LabelLayoutResponse get(String shopId, String userId) {
    checkAccess(shopId, userId);
    return get(shopId);
  }

  /**
   * {@link #shopTypeDefaults(String)} after verifying {@code userId} is a member of {@code shopId}.
   */
  public LabelLayoutResponse shopTypeDefaults(String shopId, String userId) {
    checkAccess(shopId, userId);
    return shopTypeDefaults(shopId);
  }

  /** {@link #fieldCatalog(String)} after verifying {@code userId} is a member of {@code shopId}. */
  public FieldCatalogResponse fieldCatalog(String shopId, String userId) {
    checkAccess(shopId, userId);
    return fieldCatalog(shopId);
  }

  /**
   * Validates and upserts the shop's layout (Req 2.3, 2.8, 2.9, 2.12, 3.9, 9.2, 9.3, 9.5).
   *
   * <p>Order: membership check → catalog → {@link LabelLayoutValidator#validate} → upsert by {@code
   * shopId}. Nothing is written when validation fails. The request body never carries {@code
   * shopId}; it always comes from the request context.
   *
   * @throws com.inventory.common.exception.ValidationException when the user lacks access or the
   *     request is invalid
   * @throws com.inventory.common.exception.ResourceNotFoundException when the shop does not exist
   */
  public LabelLayoutResponse save(String shopId, String userId, SaveLabelLayoutRequest req) {
    checkAccess(shopId, userId);
    FieldCatalog catalog = catalogService.catalog(shopId);
    LabelLayoutConfig config = validator.validate(req, catalog);

    LabelLayoutDocument doc =
        repository.findByShopId(shopId).orElseGet(LabelLayoutDocument::new);
    doc.setShopId(shopId);
    doc.setEnabledFieldKeys(config.enabledFieldKeys());
    doc.setStickerSize(config.stickerSize());
    doc.setShowBarcodeText(config.showBarcodeText());
    doc.setShowFieldLabels(config.showFieldLabels());
    doc.setBlankValueBehavior(config.blankValueBehavior().name());
    doc.setPrintMedia(config.printMedia().name());
    doc.setSheetPreset(config.sheetPreset());
    RollSetup roll = config.rollSetup();
    doc.setRollLabelsAcross(roll == null ? null : roll.labelsAcross());
    doc.setRollColumnGapMm(roll == null ? null : roll.columnGapMm());
    doc.setTemplate(config.template().name());
    doc.setBarcodePosition(config.barcodePosition().name());
    doc.setCurrencyStyle(config.currencyStyle().name());
    doc.setFieldZones(config.fieldZones().isEmpty() ? null : new LinkedHashMap<>(config.fieldZones()));
    doc.setFieldLabelOverrides(
        config.fieldLabelOverrides().isEmpty()
            ? null
            : new LinkedHashMap<>(config.fieldLabelOverrides()));
    doc.setUpdatedAt(Instant.now());
    doc.setUpdatedByUserId(userId);
    LabelLayoutDocument saved = repository.save(doc);

    return LabelLayoutResponse.from(
        effectiveLayout(config, catalog),
        false,
        saved.getUpdatedAt(),
        saved.getUpdatedByUserId(),
        catalog.effectiveShopType());
  }

  /**
   * The effective layout an unsaved draft would print with, worked out the way {@link #save}
   * would store it but without persisting anything. The layout screen calls this as the user
   * edits so the live preview shows server-resolved geometry (sticker size, sheet grid, roll page
   * box) instead of computing it itself, the same pattern as the stock-in {@code /preview-totals}.
   *
   * @throws com.inventory.common.exception.ValidationException when the user lacks access or the
   *     draft is invalid; the message lists every problem, exactly as on save
   */
  public LabelLayoutResponse preview(String shopId, String userId, SaveLabelLayoutRequest req) {
    checkAccess(shopId, userId);
    FieldCatalog catalog = catalogService.catalog(shopId);
    LabelLayoutConfig config = validator.validate(req, catalog);
    return LabelLayoutResponse.from(
        effectiveLayout(config, catalog), false, null, null, catalog.effectiveShopType());
  }

  // ---- unchecked operations (shop-scoped callers and tests) ----------------------------------

  /**
   * The shop's saved layout, or the Default_Layout when none is saved (Req 2.1, 2.2, 4.3, 4.4).
   * Never persists.
   *
   * @throws com.inventory.common.exception.ResourceNotFoundException when the shop does not exist
   */
  public LabelLayoutResponse get(String shopId) {
    return responseFor(shopId, catalogService.catalog(shopId));
  }

  /**
   * Suggested starting layout for the shop's type (Req 4.1, 4.2, 4.6). Always {@code
   * isDefault=true}; never persists and never touches a saved layout (Req 4.3).
   */
  public LabelLayoutResponse shopTypeDefaults(String shopId) {
    FieldCatalog catalog = catalogService.catalog(shopId);
    LabelLayoutConfig defaults = LabelLayoutDefaults.shopTypeDefaults(catalog.effectiveShopType());
    return LabelLayoutResponse.from(
        effectiveLayout(defaults, catalog), true, null, null, catalog.effectiveShopType());
  }

  /** The shop's Field_Catalog as an API response (Req 1.1). */
  public FieldCatalogResponse fieldCatalog(String shopId) {
    return FieldCatalogResponse.from(catalogService.catalog(shopId));
  }

  /**
   * The saved layout config, or the Default_Layout when none is saved. Unfiltered; pass it through
   * {@link #effectiveLayout(LabelLayoutConfig, FieldCatalog)} before rendering. Never persists.
   */
  public LabelLayoutConfig resolvedConfig(String shopId) {
    return repository
        .findByShopId(shopId)
        .map(LabelLayoutService::toConfig)
        .orElseGet(LabelLayoutDefaults::defaultLayout);
  }

  /**
   * The shop's saved-or-default layout applied against an already-built catalog; what {@code
   * BarcodeService} uses for a labels request (Req 6.11). No membership check, never persists.
   */
  public EffectiveLayout effectiveLayout(String shopId, FieldCatalog catalog) {
    return effectiveLayout(resolvedConfig(shopId), catalog);
  }

  /**
   * The saved-or-default layout for {@code shopId} as an API response against an already-built
   * catalog; what the labels endpoint embeds as {@code layout} (Req 6.12). Same shape and {@code
   * isDefault}/{@code updatedAt} semantics as {@link #get(String)}. No membership check, never
   * persists.
   */
  public LabelLayoutResponse responseFor(String shopId, FieldCatalog catalog) {
    Optional<LabelLayoutDocument> saved = repository.findByShopId(shopId);
    if (saved.isPresent()) {
      LabelLayoutDocument doc = saved.get();
      return LabelLayoutResponse.from(
          effectiveLayout(toConfig(doc), catalog),
          false,
          doc.getUpdatedAt(),
          doc.getUpdatedByUserId(),
          catalog.effectiveShopType());
    }
    return LabelLayoutResponse.from(
        effectiveLayout(LabelLayoutDefaults.defaultLayout(), catalog),
        true,
        null,
        null,
        catalog.effectiveShopType());
  }

  // ---- pure helpers --------------------------------------------------------------------------

  /**
   * Applies a config against a catalog (Req 4.5, 6.11): keeps only the enabled keys that exist in
   * the catalog and are available for {@link FieldCatalog#effectiveShopType()}, preserving relative
   * order, and enriches each with its label and value type. Unknown sticker sizes fall back to
   * {@link LabelLayoutDefaults#defaultStickerSize()}.
   */
  public EffectiveLayout effectiveLayout(LabelLayoutConfig cfg, FieldCatalog catalog) {
    ShopType shopType = catalog.effectiveShopType();
    List<EnabledFieldDto> enabled = new ArrayList<>();
    for (String key : cfg.enabledFieldKeys()) {
      catalog
          .findForUsage(key, FieldUsage.LABEL)
          .filter(field -> field.isAvailableFor(shopType))
          .map(field -> enabledField(field, cfg))
          .ifPresent(enabled::add);
    }
    StickerSizeSpec spec =
        LabelLayoutDefaults.stickerSize(cfg.stickerSize())
            .orElseGet(LabelLayoutDefaults::defaultStickerSize);

    // Resolve the sheet geometry only for SHEET layouts with a known preset (Req 10.6).
    SheetSpec sheetSpec = null;
    if (cfg.printMedia() == PrintMedia.SHEET && cfg.sheetPreset() != null) {
      sheetSpec =
          LabelLayoutDefaults.sheetPreset(cfg.sheetPreset())
              .map(preset -> SheetLayoutCalculator.resolve(preset, spec))
              .orElse(null);
    }

    // Resolve the roll page box only for ROLL layouts that saved a roll setup; legacy
    // single-column rolls stay null so the renderer keeps its old output.
    RollSpec rollSpec = null;
    if (cfg.printMedia() == PrintMedia.ROLL && cfg.rollSetup() != null) {
      rollSpec = RollLayoutCalculator.resolve(cfg.rollSetup(), spec);
    }

    return new EffectiveLayout(
        enabled,
        spec.size(),
        spec,
        cfg.showBarcodeText(),
        cfg.showFieldLabels(),
        cfg.blankValueBehavior(),
        cfg.printMedia(),
        cfg.sheetPreset(),
        sheetSpec,
        cfg.template(),
        cfg.barcodePosition(),
        cfg.currencyStyle(),
        rollSpec);
  }

  /**
   * Enriches one catalog field with its resolved {@link LabelZone} and label flag for the given
   * config (Req 11).
   *
   * <ul>
   *   <li>zone: {@code STACKED} → always {@link LabelZone#LEFT}; {@code COMPACT} → {@code
   *       fieldZones.get(key)} or {@link LabelZone#LEFT} when absent/unparseable.
   *   <li>showLabel: a per-field override wins; otherwise {@code STACKED} uses {@code
   *       showFieldLabels}, and {@code COMPACT} forces {@code HEADER} off, {@code LEFT} on, and
   *       {@code RIGHT} off for currency fields / on otherwise.
   * </ul>
   */
  static EnabledFieldDto enabledField(PrintableField field, LabelLayoutConfig cfg) {
    LabelZone zone = resolveZone(field.fieldKey(), cfg);
    boolean showLabel = resolveShowLabel(field, zone, cfg);
    return EnabledFieldDto.from(field, zone, showLabel);
  }

  private static LabelZone resolveZone(String fieldKey, LabelLayoutConfig cfg) {
    if (cfg.template() != StickerTemplate.COMPACT) {
      return LabelZone.LEFT;
    }
    return parseZone(cfg.fieldZones().get(fieldKey)).orElse(LabelZone.LEFT);
  }

  private static boolean resolveShowLabel(PrintableField field, LabelZone zone, LabelLayoutConfig cfg) {
    Boolean override = cfg.fieldLabelOverrides().get(field.fieldKey());
    if (override != null) {
      return override;
    }
    if (cfg.template() != StickerTemplate.COMPACT) {
      return cfg.showFieldLabels();
    }
    return switch (zone) {
      case HEADER -> false;
      case LEFT -> true;
      case RIGHT -> field.valueType() != ValueType.CURRENCY;
    };
  }

  static Optional<LabelZone> parseZone(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (LabelZone z : LabelZone.values()) {
      if (z.name().equals(trimmed)) {
        return Optional.of(z);
      }
    }
    return Optional.empty();
  }

  /**
   * Converts a persisted document to a config. Null or unrecognised stored values fall back to the
   * corresponding Default_Layout option so a partially written or legacy document still renders.
   */
  public static LabelLayoutConfig toConfig(LabelLayoutDocument doc) {
    LabelLayoutConfig defaults = LabelLayoutDefaults.defaultLayout();
    if (doc == null) {
      return defaults;
    }
    // Legacy documents written before the sheet-layout feature have no printMedia; they resolve to
    // ROLL with no sheet preset (Req 10.5).
    PrintMedia printMedia =
        parsePrintMedia(doc.getPrintMedia()).orElse(LabelLayoutDefaults.DEFAULT_PRINT_MEDIA);
    String sheetPreset = printMedia == PrintMedia.SHEET ? doc.getSheetPreset() : null;
    // Legacy documents written before the sticker-template feature carry no template fields; they
    // resolve to STACKED / TOP / RUPEE_SYMBOL with empty maps (Req 11).
    StickerTemplate template =
        parseTemplate(doc.getTemplate()).orElse(LabelLayoutDefaults.DEFAULT_TEMPLATE);
    BarcodePosition barcodePosition =
        parseBarcodePosition(doc.getBarcodePosition())
            .orElse(LabelLayoutDefaults.DEFAULT_BARCODE_POSITION);
    CurrencyStyle currencyStyle =
        parseCurrencyStyle(doc.getCurrencyStyle()).orElse(LabelLayoutDefaults.DEFAULT_CURRENCY_STYLE);
    // Roll setup only applies to ROLL documents that stored it; documents written before
    // multi-across rolls have neither field and keep the legacy single-column output.
    RollSetup rollSetup = printMedia == PrintMedia.ROLL ? rollSetupOf(doc) : null;
    return new LabelLayoutConfig(
        doc.getEnabledFieldKeys() == null ? defaults.enabledFieldKeys() : doc.getEnabledFieldKeys(),
        doc.getStickerSize() == null ? defaults.stickerSize() : doc.getStickerSize(),
        doc.getShowBarcodeText() == null ? defaults.showBarcodeText() : doc.getShowBarcodeText(),
        doc.getShowFieldLabels() == null ? defaults.showFieldLabels() : doc.getShowFieldLabels(),
        parseBlankValueBehavior(doc.getBlankValueBehavior()).orElse(defaults.blankValueBehavior()),
        printMedia,
        sheetPreset,
        template,
        barcodePosition,
        currencyStyle,
        doc.getFieldZones(),
        doc.getFieldLabelOverrides(),
        rollSetup);
  }

  /**
   * The stored roll setup, or {@code null} when the document carries neither roll field. A stored
   * value outside the {@link RollLayoutCalculator} bounds (hand-edited data) is clamped to its
   * default rather than failing every labels request.
   */
  private static RollSetup rollSetupOf(LabelLayoutDocument doc) {
    Integer across = doc.getRollLabelsAcross();
    Double gap = doc.getRollColumnGapMm();
    if (across == null && gap == null) {
      return null;
    }
    int safeAcross =
        across != null && RollLayoutCalculator.isValidLabelsAcross(across)
            ? across
            : RollLayoutCalculator.MIN_LABELS_ACROSS;
    double safeGap = gap != null && RollLayoutCalculator.isValidColumnGap(gap) ? gap : 0;
    return new RollSetup(safeAcross, safeGap);
  }

  private static Optional<StickerTemplate> parseTemplate(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (StickerTemplate t : StickerTemplate.values()) {
      if (t.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  private static Optional<BarcodePosition> parseBarcodePosition(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (BarcodePosition p : BarcodePosition.values()) {
      if (p.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  private static Optional<CurrencyStyle> parseCurrencyStyle(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (CurrencyStyle c : CurrencyStyle.values()) {
      if (c.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }

  private static Optional<PrintMedia> parsePrintMedia(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (PrintMedia media : PrintMedia.values()) {
      if (media.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(media);
      }
    }
    return Optional.empty();
  }

  private static Optional<BlankValueBehavior> parseBlankValueBehavior(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    for (BlankValueBehavior behavior : BlankValueBehavior.values()) {
      if (behavior.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(behavior);
      }
    }
    return Optional.empty();
  }

  private void checkAccess(String shopId, String userId) {
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
  }
}
