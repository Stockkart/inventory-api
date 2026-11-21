package com.inventory.product.labels;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Pure, stateless validation of a {@link SaveLabelLayoutRequest} against a shop's {@link
 * FieldCatalog} (Req 2.4, 2.5, 2.6, 2.10, 2.11, 3.1, 3.4, 3.7, 3.8, 3.9, 10.1, 10.4, 10.5).
 *
 * <p>Every check runs; if any fails, one {@link ValidationException} is thrown whose message joins
 * all problems with {@code "; "}. On success the request is normalized into a {@link
 * LabelLayoutConfig} with omitted options filled from {@link LabelLayoutDefaults#defaultLayout()}.
 */
@Component
public class LabelLayoutValidator {

  private static final String SHOW_BARCODE_TEXT_ERROR = "showBarcodeText must be true or false";
  private static final String SHOW_FIELD_LABELS_ERROR = "showFieldLabels must be true or false";
  private static final String BLANK_VALUE_BEHAVIOR_ERROR =
      "blankValueBehavior must be HIDE_LINE or PRINT_BLANK";
  private static final String PRINT_MEDIA_ERROR = "printMedia must be ROLL or SHEET";
  private static final String SHEET_PRESET_REQUIRED_ERROR =
      "sheetPreset is required when printMedia is SHEET";
  private static final String ROLL_LABELS_ACROSS_ERROR =
      "rollLabelsAcross must be between "
          + RollLayoutCalculator.MIN_LABELS_ACROSS
          + " and "
          + RollLayoutCalculator.MAX_LABELS_ACROSS;
  private static final String ROLL_COLUMN_GAP_ERROR =
      "rollColumnGapMm must be between 0 and " + (int) RollLayoutCalculator.MAX_COLUMN_GAP_MM;
  private static final String TEMPLATE_ERROR = "template must be STACKED or COMPACT";
  private static final String BARCODE_POSITION_ERROR = "barcodePosition must be TOP or BOTTOM";
  private static final String CURRENCY_STYLE_ERROR =
      "currencyStyle must be RUPEE_SYMBOL or RS_PREFIX";

  /**
   * Validates and normalizes the request.
   *
   * @param req the save request (never {@code null})
   * @param catalog the shop's field catalog (never {@code null})
   * @return the normalized layout configuration
   * @throws ValidationException when any check fails; the message lists every problem
   */
  public LabelLayoutConfig validate(SaveLabelLayoutRequest req, FieldCatalog catalog) {
    // LinkedHashSet keeps messages in check order while the exception joins them with "; ".
    Set<String> errors = new LinkedHashSet<>();
    LabelLayoutConfig defaults = LabelLayoutDefaults.defaultLayout();

    // ---- enabled field keys (Req 2.4, 2.5) ------------------------------------------------
    // Null-tolerant copy: a null element must be reported as unknown, not blow up the request.
    List<String> enabledKeys =
        req.enabledFieldKeys() == null
            ? List.of()
            : Collections.unmodifiableList(new ArrayList<>(req.enabledFieldKeys()));
    validateFieldKeys(enabledKeys, catalog, errors);

    // ---- sticker size (Req 2.10, 3.1) ----------------------------------------------------
    String stickerSize =
        req.stickerSize() == null ? LabelLayoutDefaults.DEFAULT_STICKER_SIZE : req.stickerSize();
    Optional<StickerSizeSpec> sizeSpec = LabelLayoutDefaults.stickerSize(stickerSize);
    if (sizeSpec.isEmpty()) {
      errors.add("stickerSize must be one of " + allowedStickerSizes());
    }

    // ---- boolean options (Req 2.11, 3.9) -------------------------------------------------
    boolean showBarcodeText = defaults.showBarcodeText();
    if (req.isShowBarcodeTextInvalid()) {
      errors.add(SHOW_BARCODE_TEXT_ERROR);
    } else if (req.showBarcodeTextValue() != null) {
      showBarcodeText = req.showBarcodeTextValue();
    }

    boolean showFieldLabels = defaults.showFieldLabels();
    if (req.isShowFieldLabelsInvalid()) {
      errors.add(SHOW_FIELD_LABELS_ERROR);
    } else if (req.showFieldLabelsValue() != null) {
      showFieldLabels = req.showFieldLabelsValue();
    }

    // ---- blank value behaviour (Req 2.11, 3.4) -------------------------------------------
    BlankValueBehavior blankValueBehavior = defaults.blankValueBehavior();
    if (req.blankValueBehavior() != null) {
      Optional<BlankValueBehavior> parsed = parseBlankValueBehavior(req.blankValueBehavior());
      if (parsed.isPresent()) {
        blankValueBehavior = parsed.get();
      } else {
        errors.add(BLANK_VALUE_BEHAVIOR_ERROR);
      }
    }

    // ---- template / barcode position / currency style (Req 11) ----------------------------
    StickerTemplate template = defaults.template();
    if (req.template() != null) {
      Optional<StickerTemplate> parsed = parseTemplate(req.template());
      if (parsed.isPresent()) {
        template = parsed.get();
      } else {
        errors.add(TEMPLATE_ERROR);
      }
    }

    BarcodePosition barcodePosition = defaults.barcodePosition();
    if (req.barcodePosition() != null) {
      Optional<BarcodePosition> parsed = parseBarcodePosition(req.barcodePosition());
      if (parsed.isPresent()) {
        barcodePosition = parsed.get();
      } else {
        errors.add(BARCODE_POSITION_ERROR);
      }
    }

    CurrencyStyle currencyStyle = defaults.currencyStyle();
    if (req.currencyStyle() != null) {
      Optional<CurrencyStyle> parsed = parseCurrencyStyle(req.currencyStyle());
      if (parsed.isPresent()) {
        currencyStyle = parsed.get();
      } else {
        errors.add(CURRENCY_STYLE_ERROR);
      }
    }

    // ---- per-field zones & label overrides (Req 11) ---------------------------------------
    // Keys not among the enabled fields are dropped silently; invalid zone values are reported.
    Set<String> enabledKeySet = new LinkedHashSet<>(enabledKeys);
    Map<String, String> fieldZones = new LinkedHashMap<>();
    if (req.fieldZones() != null) {
      for (Map.Entry<String, String> entry : req.fieldZones().entrySet()) {
        if (!enabledKeySet.contains(entry.getKey())) {
          continue;
        }
        Optional<LabelZone> zone = parseZone(entry.getValue());
        if (zone.isPresent()) {
          fieldZones.put(entry.getKey(), zone.get().name());
        } else {
          errors.add("Zone for " + entry.getKey() + " must be HEADER, LEFT or RIGHT");
        }
      }
    }
    Map<String, Boolean> fieldLabelOverrides = new LinkedHashMap<>();
    if (req.fieldLabelOverrides() != null) {
      for (Map.Entry<String, Boolean> entry : req.fieldLabelOverrides().entrySet()) {
        if (enabledKeySet.contains(entry.getKey()) && entry.getValue() != null) {
          fieldLabelOverrides.put(entry.getKey(), entry.getValue());
        }
      }
    }

    // ---- line count / zone caps, only against a valid preset (Req 2.6, 11) ----------------
    final StickerTemplate resolvedTemplate = template;
    sizeSpec.ifPresent(
        spec -> {
          if (resolvedTemplate == StickerTemplate.COMPACT) {
            validateZoneCaps(enabledKeys, fieldZones, spec, errors);
          } else if (enabledKeys.size() > spec.maxLines()) {
            errors.add(
                "Sticker "
                    + spec.size()
                    + " allows at most "
                    + spec.maxLines()
                    + " lines; "
                    + enabledKeys.size()
                    + " enabled");
          }
        });

    // ---- print media & sheet preset (Req 10.1, 10.4, 10.5) --------------------------------
    PrintMedia printMedia = PrintMedia.ROLL;
    String sheetPreset = null;
    if (req.printMedia() != null) {
      Optional<PrintMedia> parsed = parsePrintMedia(req.printMedia());
      if (parsed.isPresent()) {
        printMedia = parsed.get();
      } else {
        errors.add(PRINT_MEDIA_ERROR);
      }
    }
    if (printMedia == PrintMedia.SHEET) {
      sheetPreset = validateSheetPreset(req.sheetPreset(), stickerSize, sizeSpec, errors);
    }

    // ---- roll setup, only for ROLL and only when at least one roll field is given -----------
    RollSetup rollSetup = null;
    if (printMedia == PrintMedia.ROLL) {
      rollSetup = validateRollSetup(req.rollLabelsAcross(), req.rollColumnGapMm(), errors);
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }

    return new LabelLayoutConfig(
        enabledKeys,
        stickerSize,
        showBarcodeText,
        showFieldLabels,
        blankValueBehavior,
        printMedia,
        sheetPreset,
        template,
        barcodePosition,
        currencyStyle,
        fieldZones,
        fieldLabelOverrides,
        rollSetup);
  }

  /**
   * Validates the roll fields of a {@code ROLL} layout. Both omitted → {@code null} (legacy
   * single-column output). Otherwise a missing field takes its default ({@code 1} across, {@code 0}
   * mm gap) and each supplied field is range-checked against {@link RollLayoutCalculator}.
   */
  private static RollSetup validateRollSetup(
      Integer labelsAcross, Double columnGapMm, Set<String> errors) {
    if (labelsAcross == null && columnGapMm == null) {
      return null;
    }
    int across = labelsAcross == null ? RollLayoutCalculator.MIN_LABELS_ACROSS : labelsAcross;
    double gap = columnGapMm == null ? 0 : columnGapMm;
    boolean ok = true;
    if (!RollLayoutCalculator.isValidLabelsAcross(across)) {
      errors.add(ROLL_LABELS_ACROSS_ERROR);
      ok = false;
    }
    if (!RollLayoutCalculator.isValidColumnGap(gap)) {
      errors.add(ROLL_COLUMN_GAP_ERROR);
      ok = false;
    }
    return ok ? new RollSetup(across, gap) : null;
  }

  /**
   * Enforces the {@link StickerTemplate#COMPACT} per-zone caps for a sticker size (Req 11). Each
   * enabled field counts against its zone ({@code fieldZones.get(key)} or {@link LabelZone#LEFT}
   * when absent); a zone over its cap adds one message.
   */
  private static void validateZoneCaps(
      List<String> enabledKeys,
      Map<String, String> fieldZones,
      StickerSizeSpec spec,
      Set<String> errors) {
    Map<LabelZone, Integer> counts = new EnumMap<>(LabelZone.class);
    for (String key : enabledKeys) {
      LabelZone zone = parseZone(fieldZones.get(key)).orElse(LabelZone.LEFT);
      counts.merge(zone, 1, Integer::sum);
    }
    ZoneCaps caps = spec.zoneCaps();
    for (LabelZone zone : LabelZone.values()) {
      int count = counts.getOrDefault(zone, 0);
      int cap = caps.capFor(zone);
      if (count > cap) {
        errors.add(
            "Zone "
                + zone.name()
                + " allows at most "
                + cap
                + " fields on "
                + spec.size()
                + "; "
                + count
                + " assigned");
      }
    }
  }

  /**
   * Validates the sheet preset for a {@code SHEET} layout and returns the accepted preset id, or
   * {@code null} when a problem was recorded (Req 10.4). {@code sizeSpec} is empty when the sticker
   * size itself was already rejected; compatibility is then left unchecked.
   */
  private static String validateSheetPreset(
      String presetId,
      String stickerSize,
      Optional<StickerSizeSpec> sizeSpec,
      Set<String> errors) {
    if (presetId == null || presetId.isBlank()) {
      errors.add(SHEET_PRESET_REQUIRED_ERROR);
      return null;
    }
    Optional<SheetPreset> preset = LabelLayoutDefaults.sheetPreset(presetId);
    if (preset.isEmpty()) {
      errors.add("Unknown sheetPreset " + presetId + "; allowed: " + allowedSheetPresets());
      return null;
    }
    if (sizeSpec.isEmpty()) {
      // Sticker size invalid; its own error is already reported. Accept the preset provisionally.
      return presetId;
    }
    SheetPreset p = preset.get();
    SheetSpec spec = SheetLayoutCalculator.resolve(p, sizeSpec.get());
    if (!SheetLayoutCalculator.isCompatible(p, stickerSize) || spec.perSheet() == 0) {
      errors.add(
          "sheetPreset "
              + presetId
              + " is not compatible with "
              + stickerSize
              + "; compatible sizes: "
              + compatibleSizesText(p));
      return null;
    }
    return presetId;
  }

  private static void validateFieldKeys(
      List<String> enabledKeys, FieldCatalog catalog, Set<String> errors) {
    List<String> unknown = new ArrayList<>();
    Set<String> duplicates = new LinkedHashSet<>();
    List<String> unavailable = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    ShopType shopType = catalog.effectiveShopType();

    for (String key : enabledKeys) {
      if (!seen.add(key)) {
        duplicates.add(key);
        continue; // report unknown/unavailable once per distinct key
      }
      Optional<PrintableField> field = catalog.findForUsage(key, FieldUsage.LABEL);
      if (field.isEmpty()) {
        unknown.add(key);
      } else if (!field.get().isAvailableFor(shopType)) {
        unavailable.add(
            "Field "
                + key
                + " is only available for "
                + shopTypesInEnumOrder(field.get().availableForShopTypes()));
      }
    }

    if (!unknown.isEmpty()) {
      errors.add("Unknown fields: " + String.join(", ", unknown));
    }
    if (!duplicates.isEmpty()) {
      errors.add("Duplicate fields: " + String.join(", ", duplicates));
    }
    errors.addAll(unavailable);
  }

  private static Optional<BlankValueBehavior> parseBlankValueBehavior(String raw) {
    String trimmed = raw.trim();
    return Arrays.stream(BlankValueBehavior.values())
        .filter(b -> b.name().equals(trimmed))
        .findFirst();
  }

  private static Optional<PrintMedia> parsePrintMedia(String raw) {
    String trimmed = raw.trim();
    return Arrays.stream(PrintMedia.values()).filter(m -> m.name().equals(trimmed)).findFirst();
  }

  private static Optional<StickerTemplate> parseTemplate(String raw) {
    String trimmed = raw.trim();
    return Arrays.stream(StickerTemplate.values())
        .filter(t -> t.name().equals(trimmed))
        .findFirst();
  }

  private static Optional<BarcodePosition> parseBarcodePosition(String raw) {
    String trimmed = raw.trim();
    return Arrays.stream(BarcodePosition.values())
        .filter(p -> p.name().equals(trimmed))
        .findFirst();
  }

  private static Optional<CurrencyStyle> parseCurrencyStyle(String raw) {
    String trimmed = raw.trim();
    return Arrays.stream(CurrencyStyle.values())
        .filter(c -> c.name().equals(trimmed))
        .findFirst();
  }

  private static Optional<LabelZone> parseZone(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    return Arrays.stream(LabelZone.values()).filter(z -> z.name().equals(trimmed)).findFirst();
  }

  private static String allowedStickerSizes() {
    return LabelLayoutDefaults.STICKER_SIZES.stream()
        .map(StickerSizeSpec::size)
        .collect(Collectors.joining(", "));
  }

  private static String allowedSheetPresets() {
    return LabelLayoutDefaults.SHEET_PRESETS.stream()
        .map(SheetPreset::id)
        .collect(Collectors.joining(", "));
  }

  /**
   * The sticker sizes a preset is compatible with (and that actually fit), for the incompatibility
   * message. Die-cut presets list their declared compatible sizes; plain presets list every preset
   * size whose grid is non-empty.
   */
  private static String compatibleSizesText(SheetPreset preset) {
    if (preset.plain()) {
      return LabelLayoutDefaults.STICKER_SIZES.stream()
          .filter(s -> SheetLayoutCalculator.resolve(preset, s).perSheet() > 0)
          .map(StickerSizeSpec::size)
          .collect(Collectors.joining(", "));
    }
    return String.join(", ", preset.compatibleStickerSizes());
  }

  private static String shopTypesInEnumOrder(Set<ShopType> shopTypes) {
    return Arrays.stream(ShopType.values())
        .filter(shopTypes::contains)
        .map(ShopType::name)
        .collect(Collectors.joining(", "));
  }
}
