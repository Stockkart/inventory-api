// Feature: barcode-label-layout, Property 3: Invalid field keys are rejected and every offender is named
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property 3: Invalid field keys are rejected and every offender is named.
 *
 * <p><b>Validates: Requirements 2.4, 2.5</b>
 *
 * <p>The catalog is built from the static field sets in {@link LabelLayoutDefaults} for a
 * {@code RETAILER} shop, so {@code ptr}, {@code costPrice}, {@code saleScheme} and {@code gstRate}
 * are present but unavailable. Requests use the {@code 100x50} sticker (6 lines) and never exceed 6
 * keys so the line-count rule cannot interfere with the field-key assertions.
 */
class LabelLayoutValidatorProperties {

  /** Sticker preset with the largest line budget; keeps Req 2.6 out of the picture. */
  private static final String STICKER = "100x50";

  private static final int MAX_KEYS = 6;

  private static final String RESTRICTED_SUFFIX = " is only available for DISTRIBUTOR, WHOLESALER";

  private static final FieldCatalog CATALOG = retailerCatalog();

  private static final List<String> AVAILABLE_KEYS =
      CATALOG.fields().stream()
          .filter(f -> f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  private static final List<String> RESTRICTED_KEYS =
      CATALOG.fields().stream()
          .filter(f -> !f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  private final LabelLayoutValidator validator = new LabelLayoutValidator();

  // Feature: barcode-label-layout, Property 3: Invalid field keys are rejected and every offender is named
  @Property(tries = 100)
  void everyUnknownDuplicateAndRestrictedKeyIsNamedOrRequestIsAccepted(
      @ForAll("enabledFieldKeys") List<String> enabledFieldKeys) {
    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(enabledFieldKeys, STICKER, Boolean.TRUE, Boolean.FALSE, null);

    Set<String> unknown = new LinkedHashSet<>();
    Set<String> duplicates = new LinkedHashSet<>();
    Set<String> restricted = new LinkedHashSet<>();
    Set<String> seen = new HashSet<>();
    for (String key : enabledFieldKeys) {
      if (!seen.add(key)) {
        duplicates.add(key);
      }
      if (CATALOG.find(key).isEmpty()) {
        unknown.add(key);
      } else if (!CATALOG.find(key).get().isAvailableFor(CATALOG.effectiveShopType())) {
        restricted.add(key);
      }
    }
    boolean anyOffender = !unknown.isEmpty() || !duplicates.isEmpty() || !restricted.isEmpty();

    if (!anyOffender) {
      assertDoesNotThrow(() -> validator.validate(request, CATALOG));
      return;
    }

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(request, CATALOG));
    String message = ex.getMessage();

    for (String key : unknown) {
      assertTrue(message.contains(key), () -> "unknown key not named: " + key + " in: " + message);
    }
    for (String key : duplicates) {
      assertTrue(
          message.contains(key), () -> "duplicate key not named: " + key + " in: " + message);
    }
    for (String key : restricted) {
      assertTrue(
          message.contains("Field " + key + RESTRICTED_SUFFIX),
          () -> "restricted key not named with allowed shop types: " + key + " in: " + message);
    }
    assertFalse(message.contains("allows at most"), () -> "line-count rule leaked: " + message);
  }

  // Feature: barcode-label-layout, Property 3: Invalid field keys are rejected and every offender is named
  @Property(tries = 100)
  void requestsMadeOnlyOfDistinctAvailableKeysAreAccepted(
      @ForAll("distinctAvailableKeys") List<String> keys) {
    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(keys, STICKER, Boolean.TRUE, Boolean.FALSE, null);

    LabelLayoutConfig config = assertDoesNotThrow(() -> validator.validate(request, CATALOG));
    assertEquals(keys, config.enabledFieldKeys());
  }

  // ---- generators ----------------------------------------------------------------------------

  /**
   * A list of 0..6 keys mixing valid available keys, keys absent from the catalog, restricted keys
   * and repeats of earlier elements.
   */
  @Provide
  Arbitrary<List<String>> enabledFieldKeys() {
    Arbitrary<String> available = Arbitraries.of(AVAILABLE_KEYS);
    Arbitrary<String> restricted = Arbitraries.of(RESTRICTED_KEYS);
    Arbitrary<String> unknown =
        Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('0', '9')
            .ofMinLength(1)
            .ofMaxLength(12)
            .map(s -> "zz_" + s) // prefix guarantees no collision with any catalog key
            .filter(s -> CATALOG.find(s).isEmpty());

    // Each slot is either a fresh key of some kind, or a marker to repeat an earlier element.
    Arbitrary<String> fresh =
        Arbitraries.frequencyOf(
            Tuple.of(5, available),
            Tuple.of(2, restricted),
            Tuple.of(2, unknown));
    Arbitrary<Boolean> repeatEarlier = Arbitraries.frequency(
            Tuple.of(3, Boolean.FALSE), Tuple.of(1, Boolean.TRUE));

    Arbitrary<List<String>> freshKeys = fresh.list().ofMinSize(0).ofMaxSize(MAX_KEYS);
    Arbitrary<List<Boolean>> repeatFlags = repeatEarlier.list().ofMinSize(MAX_KEYS).ofMaxSize(MAX_KEYS);
    Arbitrary<List<Integer>> repeatSources =
        Arbitraries.integers().between(0, MAX_KEYS - 1).list().ofMinSize(MAX_KEYS).ofMaxSize(MAX_KEYS);

    return Combinators.combine(freshKeys, repeatFlags, repeatSources)
        .as(
            (keys, flags, sources) -> {
              List<String> result = new ArrayList<>(keys.size());
              for (int i = 0; i < keys.size(); i++) {
                if (i > 0 && flags.get(i)) {
                  result.add(result.get(sources.get(i) % i)); // repeat an earlier element
                } else {
                  result.add(keys.get(i));
                }
              }
              return result;
            });
  }

  /** 0..6 distinct keys that are all available to a RETAILER: the "no offender" case. */
  @Provide
  Arbitrary<List<String>> distinctAvailableKeys() {
    return Arbitraries.of(AVAILABLE_KEYS).list().uniqueElements().ofMinSize(0).ofMaxSize(MAX_KEYS);
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
