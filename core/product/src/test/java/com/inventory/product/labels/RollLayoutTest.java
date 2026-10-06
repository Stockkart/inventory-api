// Feature: barcode-label-layout, roll geometry: multi-across label rolls and the 38x38 size
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Example coverage for multi-across label rolls: the {@code 38x38} sticker size, the {@link
 * RollLayoutCalculator} page box, the validator's {@code rollLabelsAcross} / {@code rollColumnGapMm}
 * rules with their exact messages, and the service round trip from document to effective layout.
 */
class RollLayoutTest {

  private static final StickerSizeSpec S_38x38 = LabelLayoutDefaults.stickerSize("38x38").orElseThrow();
  private static final StickerSizeSpec S_50x25 = LabelLayoutDefaults.stickerSize("50x25").orElseThrow();

  private final LabelLayoutValidator validator = new LabelLayoutValidator();

  // ---- sticker size --------------------------------------------------------------------------

  @Test
  void thirtyEightSquareSizeIsOffered() {
    assertEquals(
        List.of("50x25", "38x25", "38x38", "100x50"),
        LabelLayoutDefaults.STICKER_SIZES.stream().map(StickerSizeSpec::size).toList());
    assertEquals(38, S_38x38.widthMm());
    assertEquals(38, S_38x38.heightMm());
    assertEquals(4, S_38x38.maxLines());
    assertEquals(new ZoneCaps(1, 4, 2), S_38x38.zoneCaps());
    assertEquals(new ZoneCaps(1, 4, 2), LabelLayoutDefaults.zoneCaps("38x38"));
  }

  // ---- calculator ----------------------------------------------------------------------------

  @Test
  void twoAcrossPageSpansBothLabelsAndTheGap() {
    RollSpec spec = RollLayoutCalculator.resolve(new RollSetup(2, 3), S_38x38);
    assertEquals(2, spec.labelsAcross());
    assertEquals(3.0, spec.columnGapMm());
    assertEquals(79.0, spec.pageWidthMm());
    assertEquals(38.0, spec.pageHeightMm());
  }

  @Test
  void singleAcrossPageIsTheStickerItself() {
    RollSpec spec = RollLayoutCalculator.resolve(new RollSetup(1, 5), S_50x25);
    assertEquals(50.0, spec.pageWidthMm());
    assertEquals(25.0, spec.pageHeightMm());
  }

  @Test
  void bounds() {
    assertTrue(RollLayoutCalculator.isValidLabelsAcross(1));
    assertTrue(RollLayoutCalculator.isValidLabelsAcross(4));
    assertFalse(RollLayoutCalculator.isValidLabelsAcross(0));
    assertFalse(RollLayoutCalculator.isValidLabelsAcross(5));
    assertTrue(RollLayoutCalculator.isValidColumnGap(0));
    assertTrue(RollLayoutCalculator.isValidColumnGap(20));
    assertFalse(RollLayoutCalculator.isValidColumnGap(-0.1));
    assertFalse(RollLayoutCalculator.isValidColumnGap(20.5));
    assertFalse(RollLayoutCalculator.isValidColumnGap(Double.NaN));
  }

  // ---- validator -----------------------------------------------------------------------------

  @Test
  void acceptsRollSetup() {
    LabelLayoutConfig config = validator.validate(request("ROLL", null, 2, 3.0), catalog());
    assertEquals(PrintMedia.ROLL, config.printMedia());
    assertEquals(new RollSetup(2, 3.0), config.rollSetup());
  }

  @Test
  void omittedRollFieldsLeaveSetupAbsent() {
    LabelLayoutConfig config = validator.validate(request("ROLL", null, null, null), catalog());
    assertNull(config.rollSetup());

    LabelLayoutConfig legacy =
        validator.validate(new SaveLabelLayoutRequest(List.of(), "50x25", true, false, null), catalog());
    assertNull(legacy.rollSetup());
  }

  @Test
  void partialRollFieldsFillTheOtherFromDefaults() {
    assertEquals(
        new RollSetup(3, 0.0), validator.validate(request("ROLL", null, 3, null), catalog()).rollSetup());
    assertEquals(
        new RollSetup(1, 2.5),
        validator.validate(request("ROLL", null, null, 2.5), catalog()).rollSetup());
  }

  @Test
  void sheetIgnoresRollFields() {
    LabelLayoutConfig config = validator.validate(request("SHEET", "A4_PLAIN", 2, 3.0), catalog());
    assertEquals(PrintMedia.SHEET, config.printMedia());
    assertNull(config.rollSetup());
  }

  @Test
  void labelsAcrossOutOfRangeRejected() {
    ValidationException low =
        assertThrows(
            ValidationException.class,
            () -> validator.validate(request("ROLL", null, 0, 3.0), catalog()));
    assertTrue(low.getMessage().contains("rollLabelsAcross must be between 1 and 4"), low.getMessage());

    ValidationException high =
        assertThrows(
            ValidationException.class,
            () -> validator.validate(request("ROLL", null, 5, 3.0), catalog()));
    assertTrue(high.getMessage().contains("rollLabelsAcross must be between 1 and 4"), high.getMessage());
  }

  @Test
  void columnGapOutOfRangeRejected() {
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () -> validator.validate(request("ROLL", null, 2, 25.0), catalog()));
    assertTrue(ex.getMessage().contains("rollColumnGapMm must be between 0 and 20"), ex.getMessage());
  }

  @Test
  void thirtyEightSquareAllowsFourStackedLines() {
    FieldCatalog catalog = retailerCatalog();
    List<String> four = List.of("productName", "companyName", "mrp", "hsn");
    assertEquals(4, validator.validate(new SaveLabelLayoutRequest(four, "38x38", true, false, null), catalog).enabledFieldKeys().size());

    List<String> five = List.of("productName", "companyName", "mrp", "hsn", "baseUnit");
    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () -> validator.validate(new SaveLabelLayoutRequest(five, "38x38", true, false, null), catalog));
    assertTrue(ex.getMessage().contains("Sticker 38x38 allows at most 4 lines; 5 enabled"), ex.getMessage());
  }

  // ---- service: document → config → effective layout -----------------------------------------

  @Test
  void documentRoundTripResolvesRollSpec() {
    LabelLayoutDocument doc = new LabelLayoutDocument();
    doc.setShopId("shop");
    doc.setStickerSize("38x38");
    doc.setPrintMedia("ROLL");
    doc.setRollLabelsAcross(2);
    doc.setRollColumnGapMm(3.0);

    LabelLayoutConfig config = LabelLayoutService.toConfig(doc);
    assertEquals(new RollSetup(2, 3.0), config.rollSetup());

    EffectiveLayout effective = effectiveLayout(config);
    assertNotNull(effective.rollSpec());
    assertEquals(79.0, effective.rollSpec().pageWidthMm());
    assertEquals(38.0, effective.rollSpec().pageHeightMm());
    assertNull(effective.sheetSpec());

    LabelLayoutResponse response =
        LabelLayoutResponse.from(effective, false, null, null, ShopType.RETAILER);
    assertEquals(effective.rollSpec(), response.rollSpec());
    assertEquals(effective.rollSpec(), response.toEffectiveLayout().rollSpec());
  }

  @Test
  void legacyDocumentHasNoRollSpec() {
    LabelLayoutDocument doc = new LabelLayoutDocument();
    doc.setShopId("shop");
    doc.setStickerSize("50x25");

    LabelLayoutConfig config = LabelLayoutService.toConfig(doc);
    assertNull(config.rollSetup());
    assertNull(effectiveLayout(config).rollSpec());
    assertNull(effectiveLayout(LabelLayoutDefaults.defaultLayout()).rollSpec());
  }

  @Test
  void sheetDocumentIgnoresStoredRollFields() {
    LabelLayoutDocument doc = new LabelLayoutDocument();
    doc.setShopId("shop");
    doc.setStickerSize("50x25");
    doc.setPrintMedia("SHEET");
    doc.setSheetPreset("A4_40UP");
    doc.setRollLabelsAcross(2);
    doc.setRollColumnGapMm(3.0);

    LabelLayoutConfig config = LabelLayoutService.toConfig(doc);
    assertNull(config.rollSetup());
    assertNull(effectiveLayout(config).rollSpec());
    assertNotNull(effectiveLayout(config).sheetSpec());
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static SaveLabelLayoutRequest request(
      String printMedia, String sheetPreset, Integer across, Double gapMm) {
    return new SaveLabelLayoutRequest(
        List.of(),
        "38x38",
        LenientBoolean.of(true),
        LenientBoolean.of(false),
        null,
        printMedia,
        sheetPreset,
        null,
        null,
        null,
        null,
        null,
        across,
        gapMm);
  }

  private static EffectiveLayout effectiveLayout(LabelLayoutConfig config) {
    return new LabelLayoutService(null, null, null, null, null).effectiveLayout(config, catalog());
  }

  private static FieldCatalog catalog() {
    return new FieldCatalog(
        List.of(), LabelLayoutDefaults.STICKER_SIZES, ShopType.RETAILER, true, List.of(), List.of());
  }

  private static FieldCatalog retailerCatalog() {
    List<PrintableField> fields = new ArrayList<>();
    fields.addAll(LabelLayoutDefaults.coreFields());
    fields.addAll(LabelLayoutDefaults.pricingFields());
    return new FieldCatalog(
        fields, LabelLayoutDefaults.STICKER_SIZES, ShopType.RETAILER, true, List.of(), List.of());
  }
}
