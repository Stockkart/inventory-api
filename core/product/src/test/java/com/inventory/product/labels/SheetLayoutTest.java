// Feature: barcode-label-layout, Requirement 10: Print media and sheet layout (backend)
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Example coverage for the backend print-media / sheet-layout model (Req 10.1–10.6): the preset
 * table, the {@link SheetLayoutCalculator} floor formula and die-cut copy, compatibility, defaults
 * and the {@link LabelLayoutValidator} SHEET rules with their exact messages.
 */
class SheetLayoutTest {

  private static final StickerSizeSpec S_50x25 = LabelLayoutDefaults.stickerSize("50x25").orElseThrow();
  private static final StickerSizeSpec S_38x25 = LabelLayoutDefaults.stickerSize("38x25").orElseThrow();
  private static final StickerSizeSpec S_100x50 = LabelLayoutDefaults.stickerSize("100x50").orElseThrow();

  private final LabelLayoutValidator validator = new LabelLayoutValidator();

  // ---- presets & defaults --------------------------------------------------------------------

  @Test
  void sixPresetsInOrderWithDefaultRoll() {
    assertEquals(
        List.of("A4_PLAIN", "LETTER_PLAIN", "A4_65UP", "A4_40UP", "A4_24UP", "A4_12UP"),
        LabelLayoutDefaults.SHEET_PRESETS.stream().map(SheetPreset::id).toList());
    assertEquals(PrintMedia.ROLL, LabelLayoutDefaults.DEFAULT_PRINT_MEDIA);

    LabelLayoutConfig def = LabelLayoutDefaults.defaultLayout();
    assertEquals(PrintMedia.ROLL, def.printMedia());
    assertNull(def.sheetPreset());

    LabelLayoutConfig trade = LabelLayoutDefaults.shopTypeDefaults(ShopType.WHOLESALER);
    assertEquals(PrintMedia.ROLL, trade.printMedia());
    assertNull(trade.sheetPreset());
  }

  @Test
  void presetLookup() {
    assertTrue(LabelLayoutDefaults.sheetPreset("A4_40UP").isPresent());
    assertTrue(LabelLayoutDefaults.sheetPreset("missing").isEmpty());
    assertTrue(LabelLayoutDefaults.sheetPreset(null).isEmpty());
  }

  // ---- plain preset: derived grid (floor formula, gutter 2) ----------------------------------

  @Test
  void plainPresetDerivesGridWithGutter() {
    SheetPreset a4Plain = LabelLayoutDefaults.sheetPreset("A4_PLAIN").orElseThrow();

    SheetSpec s50 = SheetLayoutCalculator.resolve(a4Plain, S_50x25);
    assertEquals(3, s50.columns());
    assertEquals(10, s50.rows());
    assertEquals(30, s50.perSheet());
    assertEquals(52.0, s50.pitchXMm());
    assertEquals(27.0, s50.pitchYMm());

    SheetSpec s38 = SheetLayoutCalculator.resolve(a4Plain, S_38x25);
    assertEquals(4, s38.columns());
    assertEquals(10, s38.rows());
    assertEquals(40, s38.perSheet());

    SheetSpec s100 = SheetLayoutCalculator.resolve(a4Plain, S_100x50);
    assertEquals(1, s100.columns());
    assertEquals(5, s100.rows());
    assertEquals(5, s100.perSheet());
  }

  // ---- die-cut preset: copied grid -----------------------------------------------------------

  @Test
  void dieCutPresetCopiesGrid() {
    SheetPreset a4_65 = LabelLayoutDefaults.sheetPreset("A4_65UP").orElseThrow();
    SheetSpec spec = SheetLayoutCalculator.resolve(a4_65, S_38x25);
    assertEquals(5, spec.columns());
    assertEquals(13, spec.rows());
    assertEquals(65, spec.perSheet());
    assertEquals(38.1, spec.pitchXMm());
    assertEquals(21.2, spec.pitchYMm());
    // The grid is fixed regardless of the sticker size passed in.
    assertEquals(65, SheetLayoutCalculator.resolve(a4_65, S_50x25).perSheet());
  }

  // ---- compatibility -------------------------------------------------------------------------

  @Test
  void compatibility() {
    SheetPreset a4Plain = LabelLayoutDefaults.sheetPreset("A4_PLAIN").orElseThrow();
    SheetPreset a4_65 = LabelLayoutDefaults.sheetPreset("A4_65UP").orElseThrow();
    SheetPreset a4_12 = LabelLayoutDefaults.sheetPreset("A4_12UP").orElseThrow();

    // Plain presets accept every known sticker size, nothing else.
    assertTrue(SheetLayoutCalculator.isCompatible(a4Plain, "50x25"));
    assertTrue(SheetLayoutCalculator.isCompatible(a4Plain, "100x50"));
    assertFalse(SheetLayoutCalculator.isCompatible(a4Plain, "99x99"));

    // Die-cut presets accept exactly their compatible list.
    assertTrue(SheetLayoutCalculator.isCompatible(a4_65, "38x25"));
    assertFalse(SheetLayoutCalculator.isCompatible(a4_65, "50x25"));
    assertTrue(SheetLayoutCalculator.isCompatible(a4_12, "100x50"));
    assertTrue(SheetLayoutCalculator.isCompatible(a4_12, "50x25"));
  }

  // ---- validator SHEET rules -----------------------------------------------------------------

  @Test
  void acceptsCompatibleSheetLayout() {
    LabelLayoutConfig config =
        validator.validate(
            new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null, "SHEET", "A4_40UP"),
            catalog());
    assertEquals(PrintMedia.SHEET, config.printMedia());
    assertEquals("A4_40UP", config.sheetPreset());
  }

  @Test
  void rollIgnoresSheetPreset() {
    LabelLayoutConfig config =
        validator.validate(
            new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null, "ROLL", "A4_40UP"),
            catalog());
    assertEquals(PrintMedia.ROLL, config.printMedia());
    assertNull(config.sheetPreset());
  }

  @Test
  void omittedPrintMediaDefaultsToRoll() {
    LabelLayoutConfig config =
        validator.validate(
            new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null), catalog());
    assertEquals(PrintMedia.ROLL, config.printMedia());
    assertNull(config.sheetPreset());
  }

  @Test
  void invalidPrintMediaRejected() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () ->
                validator.validate(
                    new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null, "PAPER", null),
                    catalog()));
    assertTrue(ex.getMessage().contains("printMedia must be ROLL or SHEET"), ex.getMessage());
  }

  @Test
  void sheetPresetRequiredWhenSheet() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () ->
                validator.validate(
                    new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null, "SHEET", null),
                    catalog()));
    assertTrue(
        ex.getMessage().contains("sheetPreset is required when printMedia is SHEET"),
        ex.getMessage());
  }

  @Test
  void unknownSheetPresetRejectedWithAllowedList() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () ->
                validator.validate(
                    new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null, "SHEET", "FOO"),
                    catalog()));
    assertTrue(
        ex.getMessage()
            .contains(
                "Unknown sheetPreset FOO; allowed: A4_PLAIN, LETTER_PLAIN, A4_65UP, A4_40UP, A4_24UP, A4_12UP"),
        ex.getMessage());
  }

  @Test
  void incompatibleSheetPresetRejectedWithCompatibleSizes() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () ->
                validator.validate(
                    new SaveLabelLayoutRequest(
                        List.of(), "50x25", true, false, null, "SHEET", "A4_65UP"),
                    catalog()));
    assertTrue(
        ex.getMessage()
            .contains("sheetPreset A4_65UP is not compatible with 50x25; compatible sizes: 38x25"),
        ex.getMessage());
  }

  private static FieldCatalog catalog() {
    return new FieldCatalog(
        List.of(),
        LabelLayoutDefaults.STICKER_SIZES,
        ShopType.RETAILER,
        true,
        List.of(),
        List.of());
  }
}
