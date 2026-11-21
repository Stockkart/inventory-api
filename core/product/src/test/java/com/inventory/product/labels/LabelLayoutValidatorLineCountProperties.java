// Feature: barcode-label-layout, Property 4: Enabled count is accepted exactly up to the sticker's maximum
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property 4: Enabled count is accepted exactly up to the sticker's maximum.
 *
 * <p><b>Validates: Requirements 2.6, 3.5</b>
 *
 * <p>For every sticker preset in {@link LabelLayoutDefaults#STICKER_SIZES} and every count {@code n}
 * in {@code [0, maxLines + 5]}, a request with {@code n} distinct keys available to a {@code
 * RETAILER} is accepted iff {@code n <= maxLines}. When rejected, the single {@link
 * ValidationException} names the sticker's maximum and the enabled count.
 */
class LabelLayoutValidatorLineCountProperties {

  private static final int OVERSHOOT = 5;

  private static final FieldCatalog CATALOG = retailerCatalog();

  private static final List<String> AVAILABLE_KEYS =
      CATALOG.fields().stream()
          .filter(f -> f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  private final LabelLayoutValidator validator = new LabelLayoutValidator();

  // Feature: barcode-label-layout, Property 4: Enabled count is accepted exactly up to the sticker's maximum
  @Property(tries = 100)
  void enabledCountIsAcceptedExactlyUpToMaxLines(
      @ForAll("sizeAndKeys") Tuple.Tuple2<StickerSizeSpec, List<String>> input) {
    StickerSizeSpec spec = input.get1();
    List<String> keys = input.get2();
    int n = keys.size();
    int maxLines = spec.maxLines();

    SaveLabelLayoutRequest request =
        new SaveLabelLayoutRequest(keys, spec.size(), Boolean.TRUE, Boolean.FALSE, null);

    if (n <= maxLines) {
      LabelLayoutConfig config = assertDoesNotThrow(() -> validator.validate(request, CATALOG));
      assertEquals(keys, config.enabledFieldKeys());
      assertEquals(spec.size(), config.stickerSize());
      return;
    }

    ValidationException ex =
        assertThrows(ValidationException.class, () -> validator.validate(request, CATALOG));
    String message = ex.getMessage();
    assertTrue(
        message.contains("allows at most " + maxLines),
        () -> "max lines not named for " + spec.size() + ": " + message);
    assertTrue(
        message.contains(n + " enabled"), () -> "enabled count not named (" + n + "): " + message);
    // Only the line-count rule should fire: the message is exactly that one line.
    assertEquals(
        "Sticker " + spec.size() + " allows at most " + maxLines + " lines; " + n + " enabled",
        message);
  }

  // ---- generators ----------------------------------------------------------------------------

  /** A sticker preset paired with {@code n} distinct available keys, {@code n in [0, maxLines+5]}. */
  @Provide
  Arbitrary<Tuple.Tuple2<StickerSizeSpec, List<String>>> sizeAndKeys() {
    int maxCount =
        LabelLayoutDefaults.STICKER_SIZES.stream().mapToInt(StickerSizeSpec::maxLines).max().orElse(0)
            + OVERSHOOT;
    assertTrue(
        AVAILABLE_KEYS.size() >= maxCount,
        () -> "catalog has only " + AVAILABLE_KEYS.size() + " available keys; need " + maxCount);

    Arbitrary<StickerSizeSpec> sizes = Arbitraries.of(LabelLayoutDefaults.STICKER_SIZES);
    Arbitrary<List<String>> shuffled = Arbitraries.shuffle(AVAILABLE_KEYS);

    return Combinators.combine(sizes, shuffled)
        .flatAs(
            (spec, order) ->
                Arbitraries.integers()
                    .between(0, spec.maxLines() + OVERSHOOT)
                    .map(n -> Tuple.of(spec, List.copyOf(order.subList(0, n)))));
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
