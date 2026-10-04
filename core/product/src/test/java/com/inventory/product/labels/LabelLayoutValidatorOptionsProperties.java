// Feature: barcode-label-layout, Property 5: Non-preset sizes and non-enum options are rejected with the allowed values
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property 5: Non-preset sizes and non-enum options are rejected with the allowed values.
 *
 * <p><b>Validates: Requirements 2.10, 2.11, 3.1, 3.4</b>
 *
 * <p>Every request uses an empty {@code enabledFieldKeys} list so the field-key and line-count rules
 * can never contribute to the outcome; only the option checks are under test.
 */
class LabelLayoutValidatorOptionsProperties {

  private static final String STICKER_SIZE_ERROR = "stickerSize must be one of 50x25, 38x25, 100x50";
  private static final String BLANK_VALUE_BEHAVIOR_ERROR =
      "blankValueBehavior must be HIDE_LINE or PRINT_BLANK";

  private static final Set<String> PRESET_SIZES = Set.of("50x25", "38x25", "100x50");
  private static final Set<String> ENUM_NAMES = Set.of("HIDE_LINE", "PRINT_BLANK");

  private static final FieldCatalog CATALOG = retailerCatalog();

  private final LabelLayoutValidator validator = new LabelLayoutValidator();

  // Feature: barcode-label-layout, Property 5: Non-preset sizes and non-enum options are rejected with the allowed values
  @Property(tries = 100)
  void nonPresetStickerSizeIsRejectedWithAllowedValues(
      @ForAll("nonPresetSize") String stickerSize) {
    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(List.of(), stickerSize, Boolean.TRUE, Boolean.FALSE, null);

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(request, CATALOG));
    assertTrue(
        ex.getMessage().contains(STICKER_SIZE_ERROR),
        () -> "size '" + stickerSize + "' rejected without allowed values: " + ex.getMessage());
  }

  // Feature: barcode-label-layout, Property 5: Non-preset sizes and non-enum options are rejected with the allowed values
  @Property(tries = 100)
  void nonEnumBlankValueBehaviorIsRejectedWithAllowedValues(
      @ForAll("nonEnumBehavior") String behavior) {
    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(List.of(), "50x25", Boolean.TRUE, Boolean.FALSE, behavior);

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(request, CATALOG));
    assertTrue(
        ex.getMessage().contains(BLANK_VALUE_BEHAVIOR_ERROR),
        () -> "behavior '" + behavior + "' rejected without allowed values: " + ex.getMessage());
  }

  // Feature: barcode-label-layout, Property 5: Non-preset sizes and non-enum options are rejected with the allowed values
  @Property(tries = 100)
  void nonBooleanOptionsAreRejectedNamingTheOption(
      @ForAll("invalidRawBoolean") String rawBarcodeText,
      @ForAll("invalidRawBoolean") String rawFieldLabels,
      @ForAll boolean breakBarcodeText,
      @ForAll boolean breakFieldLabels) {
    // Ensure at least one option is invalid so a rejection is expected.
    boolean badBarcodeText = breakBarcodeText || !breakFieldLabels;
    boolean badFieldLabels = breakFieldLabels;

    LenientBoolean showBarcodeText =
        badBarcodeText ? LenientBoolean.invalid(rawBarcodeText) : LenientBoolean.of(true);
    LenientBoolean showFieldLabels =
        badFieldLabels ? LenientBoolean.invalid(rawFieldLabels) : LenientBoolean.of(false);
    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(List.of(), "50x25", showBarcodeText, showFieldLabels, null);

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(request, CATALOG));
    String message = ex.getMessage();

    assertEquals(
        badBarcodeText,
        message.contains("showBarcodeText must be true or false"),
        () -> "showBarcodeText error presence mismatch: " + message);
    assertEquals(
        badFieldLabels,
        message.contains("showFieldLabels must be true or false"),
        () -> "showFieldLabels error presence mismatch: " + message);
  }

  // Feature: barcode-label-layout, Property 5: Non-preset sizes and non-enum options are rejected with the allowed values
  @Property(tries = 100)
  void validOptionsAreAcceptedAndCarriedIntoTheConfig(
      @ForAll("presetSize") String stickerSize,
      @ForAll BlankValueBehavior behavior,
      @ForAll("optionalBoolean") Boolean showBarcodeText,
      @ForAll("optionalBoolean") Boolean showFieldLabels) {
    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(
            List.of(), stickerSize, showBarcodeText, showFieldLabels, behavior.name());

    LabelLayoutConfig config = assertDoesNotThrow(() -> validator.validate(request, CATALOG));
    LabelLayoutConfig defaults = LabelLayoutDefaults.defaultLayout();

    assertEquals(stickerSize, config.stickerSize());
    assertEquals(behavior, config.blankValueBehavior());
    assertEquals(
        showBarcodeText == null ? defaults.showBarcodeText() : showBarcodeText,
        config.showBarcodeText());
    assertEquals(
        showFieldLabels == null ? defaults.showFieldLabels() : showFieldLabels,
        config.showFieldLabels());
    assertTrue(config.enabledFieldKeys().isEmpty());
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<String> presetSize() {
    return Arbitraries.of(PRESET_SIZES);
  }

  /**
   * Strings that are not exactly a preset id: blank, case variants, padded presets, near-misses
   * such as {@code 50x26}, and random text.
   */
  @Provide
  Arbitrary<String> nonPresetSize() {
    Arbitrary<String> blank = Arbitraries.of("", " ", "\t");
    Arbitrary<String> caseVariant = Arbitraries.of(PRESET_SIZES).map(String::toUpperCase);
    Arbitrary<String> padded =
        Arbitraries.of(PRESET_SIZES)
            .flatMap(
                s -> Arbitraries.of(s + " ", " " + s, s + "\n", s + "mm"));
    Arbitrary<String> nearMiss =
        Arbitraries.integers()
            .between(1, 200)
            .flatMap(
                w ->
                    Arbitraries.integers()
                        .between(1, 200)
                        .map(h -> w + "x" + h));
    Arbitrary<String> random =
        Arbitraries.strings().withCharRange(' ', '~').ofMinLength(1).ofMaxLength(12);
    return Arbitraries.frequencyOf(
            Tuple.of(1, blank),
            Tuple.of(2, caseVariant),
            Tuple.of(2, padded),
            Tuple.of(3, nearMiss),
            Tuple.of(3, random))
        .filter(s -> !PRESET_SIZES.contains(s));
  }

  /**
   * Strings whose trimmed form is not exactly an enum name: blank, lowercase/mixed-case variants,
   * near-misses such as {@code HIDE_LINES}, and random text.
   */
  @Provide
  Arbitrary<String> nonEnumBehavior() {
    Arbitrary<String> blank = Arbitraries.of("", " ", "\t");
    Arbitrary<String> lower = Arbitraries.of(ENUM_NAMES).map(String::toLowerCase);
    Arbitrary<String> mixed =
        Arbitraries.of(ENUM_NAMES)
            .map(s -> s.charAt(0) + s.substring(1).toLowerCase());
    Arbitrary<String> nearMiss =
        Arbitraries.of(ENUM_NAMES)
            .flatMap(s -> Arbitraries.of(s + "S", s.replace('_', '-'), s.replace("_", "")));
    Arbitrary<String> random =
        Arbitraries.strings().withCharRange(' ', '~').ofMinLength(1).ofMaxLength(16);
    return Arbitraries.frequencyOf(
            Tuple.of(1, blank),
            Tuple.of(2, lower),
            Tuple.of(2, mixed),
            Tuple.of(2, nearMiss),
            Tuple.of(3, random))
        .filter(s -> !ENUM_NAMES.contains(s.trim()));
  }

  /** Raw JSON text that the lenient deserializer would have flagged as non-boolean. */
  @Provide
  Arbitrary<String> invalidRawBoolean() {
    Arbitrary<String> wellKnown = Arbitraries.of("yes", "no", "1", "0", "[]", "{}", "\"\"", "T");
    Arbitrary<String> random =
        Arbitraries.strings().withCharRange(' ', '~').ofMinLength(0).ofMaxLength(8);
    return Arbitraries.frequencyOf(Tuple.of(1, wellKnown), Tuple.of(1, random))
        .filter(s -> !s.trim().equalsIgnoreCase("true") && !s.trim().equalsIgnoreCase("false"));
  }

  @Provide
  Arbitrary<Boolean> optionalBoolean() {
    return Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3);
  }

  private static FieldCatalog retailerCatalog() {
    List<PrintableField> fields = new ArrayList<>();
    fields.addAll(LabelLayoutDefaults.coreFields());
    fields.addAll(LabelLayoutDefaults.pricingFields());
    fields.addAll(LabelLayoutDefaults.lotFields());
    fields.addAll(LabelLayoutDefaults.shopFields());
    return new FieldCatalog(
        fields,
        LabelLayoutDefaults.STICKER_SIZES,
        ShopType.RETAILER,
        true,
        List.of(),
        List.of());
  }
}
