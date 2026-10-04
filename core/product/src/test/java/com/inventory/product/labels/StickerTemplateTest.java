package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Requirement 11 (Sticker templates): zone resolution, the per-field {@code showLabel} rule,
 * COMPACT zone-cap validation messages, and the {@code Rs.} currency style.
 */
class StickerTemplateTest {

  private static final Set<ShopType> ALL =
      Set.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER);

  private final LabelLayoutValidator validator = new LabelLayoutValidator();

  // ---- zone resolution -----------------------------------------------------------------------

  @Test
  void stackedTemplateAlwaysResolvesLeftZoneRegardlessOfFieldZones() {
    LabelLayoutConfig cfg =
        config(StickerTemplate.STACKED, "50x25", Map.of("mrp", "RIGHT"), Map.of(), false);

    assertEquals(LabelZone.LEFT, LabelLayoutService.enabledField(field("mrp", ValueType.CURRENCY), cfg).zone());
  }

  @Test
  void compactUsesFieldZonesAndDefaultsMissingKeysToLeft() {
    LabelLayoutConfig cfg =
        config(
            StickerTemplate.COMPACT,
            "50x25",
            Map.of("mrp", "RIGHT", "shopName", "HEADER"),
            Map.of(),
            false);

    assertEquals(LabelZone.RIGHT, LabelLayoutService.enabledField(field("mrp", ValueType.CURRENCY), cfg).zone());
    assertEquals(LabelZone.HEADER, LabelLayoutService.enabledField(field("shopName", ValueType.TEXT), cfg).zone());
    // Not in the map → LEFT.
    assertEquals(LabelZone.LEFT, LabelLayoutService.enabledField(field("hsn", ValueType.TEXT), cfg).zone());
  }

  // ---- showLabel rule ------------------------------------------------------------------------

  @Test
  void labelOverrideWinsOverEveryAutomaticRule() {
    // COMPACT + LEFT would be true automatically, but the override forces it off.
    LabelLayoutConfig off =
        config(StickerTemplate.COMPACT, "50x25", Map.of(), Map.of("hsn", false), false);
    assertFalse(LabelLayoutService.enabledField(field("hsn", ValueType.TEXT), off).showLabel());

    // COMPACT + HEADER would be false automatically, but the override forces it on.
    LabelLayoutConfig on =
        config(
            StickerTemplate.COMPACT,
            "50x25",
            Map.of("shopName", "HEADER"),
            Map.of("shopName", true),
            false);
    assertTrue(LabelLayoutService.enabledField(field("shopName", ValueType.TEXT), on).showLabel());
  }

  @Test
  void stackedShowLabelFollowsShowFieldLabels() {
    LabelLayoutConfig labelsOn = config(StickerTemplate.STACKED, "50x25", Map.of(), Map.of(), true);
    LabelLayoutConfig labelsOff = config(StickerTemplate.STACKED, "50x25", Map.of(), Map.of(), false);

    assertTrue(LabelLayoutService.enabledField(field("mrp", ValueType.CURRENCY), labelsOn).showLabel());
    assertFalse(LabelLayoutService.enabledField(field("mrp", ValueType.CURRENCY), labelsOff).showLabel());
  }

  @Test
  void compactShowLabelDependsOnZoneAndCurrency() {
    Map<String, String> zones =
        Map.of("header", "HEADER", "leftText", "LEFT", "rightMoney", "RIGHT", "rightText", "RIGHT");
    LabelLayoutConfig cfg = config(StickerTemplate.COMPACT, "50x25", zones, Map.of(), true);

    // HEADER → never labelled.
    assertFalse(LabelLayoutService.enabledField(field("header", ValueType.TEXT), cfg).showLabel());
    // LEFT → always labelled.
    assertTrue(LabelLayoutService.enabledField(field("leftText", ValueType.TEXT), cfg).showLabel());
    // RIGHT + currency → not labelled.
    assertFalse(LabelLayoutService.enabledField(field("rightMoney", ValueType.CURRENCY), cfg).showLabel());
    // RIGHT + non-currency → labelled.
    assertTrue(LabelLayoutService.enabledField(field("rightText", ValueType.TEXT), cfg).showLabel());
  }

  // ---- zone-cap validation -------------------------------------------------------------------

  @Test
  void compactZoneCapExceededReportsPerZoneMessage() {
    // Five fields, all defaulting to LEFT, on 50x25 whose LEFT cap is 4.
    List<String> keys =
        List.of("productName", "companyName", "hsn", "baseUnit", "packSize");
    SaveLabelLayoutRequest req = compactRequest(keys, "50x25", Map.of(), Map.of());

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(req, retailerCatalog()));
    assertTrue(
        ex.getMessage().contains("Zone LEFT allows at most 4 fields on 50x25; 5 assigned"),
        ex.getMessage());
    // COMPACT skips the STACKED line-count rule entirely.
    assertFalse(ex.getMessage().contains("allows at most 3 lines"), ex.getMessage());
  }

  @Test
  void compactRespectsZoneAssignmentsWithinCaps() {
    // 50x25 caps: header 1, left 4, right 2. Spread the fields so no zone is exceeded.
    List<String> keys =
        List.of("shopName", "productName", "companyName", "hsn", "baseUnit", "mrp", "sellingPrice");
    Map<String, String> zones =
        Map.of(
            "shopName", "HEADER",
            "productName", "LEFT",
            "companyName", "LEFT",
            "hsn", "LEFT",
            "baseUnit", "LEFT",
            "mrp", "RIGHT",
            "sellingPrice", "RIGHT");
    SaveLabelLayoutRequest req = compactRequest(keys, "50x25", zones, Map.of());

    LabelLayoutConfig cfg = validator.validate(req, retailerCatalog());
    assertEquals(StickerTemplate.COMPACT, cfg.template());
    assertEquals("RIGHT", cfg.fieldZones().get("mrp"));
  }

  @Test
  void invalidZoneValueIsReported() {
    SaveLabelLayoutRequest req =
        compactRequest(List.of("mrp"), "50x25", Map.of("mrp", "MIDDLE"), Map.of());

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(req, retailerCatalog()));
    assertTrue(
        ex.getMessage().contains("Zone for mrp must be HEADER, LEFT or RIGHT"), ex.getMessage());
  }

  @Test
  void unknownTemplateAndCurrencyStyleAreReported() {
    SaveLabelLayoutRequest req =
        new SaveLabelLayoutRequest(
            List.of("productName"),
            "50x25",
            LenientBoolean.of(true),
            LenientBoolean.of(false),
            null,
            null,
            null,
            "FANCY",
            null,
            "DOLLARS",
            Map.of(),
            Map.of());

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(req, retailerCatalog()));
    assertTrue(ex.getMessage().contains("template must be STACKED or COMPACT"), ex.getMessage());
    assertTrue(
        ex.getMessage().contains("currencyStyle must be RUPEE_SYMBOL or RS_PREFIX"), ex.getMessage());
  }

  @Test
  void stackedTemplateStillEnforcesMaxLines() {
    List<String> keys = List.of("productName", "companyName", "hsn", "baseUnit", "packSize");
    SaveLabelLayoutRequest req =
        new SaveLabelLayoutRequest(keys, "50x25", true, false, null);

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(req, retailerCatalog()));
    assertTrue(
        ex.getMessage().contains("Sticker 50x25 allows at most 3 lines; 5 enabled"), ex.getMessage());
  }

  @Test
  void keysNotInEnabledFieldsAreDroppedSilently() {
    SaveLabelLayoutRequest req =
        compactRequest(
            List.of("productName"),
            "50x25",
            Map.of("notEnabled", "RIGHT"),
            Map.of("alsoNotEnabled", true));

    LabelLayoutConfig cfg = validator.validate(req, retailerCatalog());
    assertFalse(cfg.fieldZones().containsKey("notEnabled"));
    assertFalse(cfg.fieldLabelOverrides().containsKey("alsoNotEnabled"));
  }

  // ---- Rs. formatting ------------------------------------------------------------------------

  @Test
  void rsPrefixCurrencyStyleRendersAsciiPrefix() {
    assertEquals(
        "Rs. 120.00",
        LabelValueFormatter.format(new BigDecimal("120"), ValueType.CURRENCY, CurrencyStyle.RS_PREFIX));
  }

  @Test
  void rupeeSymbolStyleAndTwoArgFormatKeepTheGlyph() {
    assertEquals(
        "\u20B9120.00",
        LabelValueFormatter.format(
            new BigDecimal("120"), ValueType.CURRENCY, CurrencyStyle.RUPEE_SYMBOL));
    assertEquals("\u20B9120.00", LabelValueFormatter.format(new BigDecimal("120"), ValueType.CURRENCY));
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static PrintableField field(String key, ValueType type) {
    return new PrintableField(key, key, SourceGroup.PRICING, type, ALL);
  }

  private static LabelLayoutConfig config(
      StickerTemplate template,
      String size,
      Map<String, String> zones,
      Map<String, Boolean> overrides,
      boolean showFieldLabels) {
    return new LabelLayoutConfig(
        List.of(),
        size,
        true,
        showFieldLabels,
        BlankValueBehavior.HIDE_LINE,
        PrintMedia.ROLL,
        null,
        template,
        BarcodePosition.TOP,
        CurrencyStyle.RUPEE_SYMBOL,
        zones,
        overrides);
  }

  private static SaveLabelLayoutRequest compactRequest(
      List<String> keys,
      String size,
      Map<String, String> zones,
      Map<String, Boolean> overrides) {
    return new SaveLabelLayoutRequest(
        keys,
        size,
        LenientBoolean.of(true),
        LenientBoolean.of(false),
        null,
        null,
        null,
        "COMPACT",
        null,
        null,
        zones,
        overrides);
  }

  private static FieldCatalog retailerCatalog() {
    List<PrintableField> fields = new ArrayList<>();
    fields.addAll(LabelLayoutDefaults.coreFields());
    fields.addAll(LabelLayoutDefaults.pricingFields());
    fields.addAll(LabelLayoutDefaults.lotFields());
    fields.addAll(LabelLayoutDefaults.shopFields());
    return new FieldCatalog(
        fields, LabelLayoutDefaults.STICKER_SIZES, ShopType.RETAILER, true, List.of(), List.of());
  }
}
