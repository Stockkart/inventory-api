// Feature: barcode-label-layout, Property 8: A rejected save changes nothing
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.LenientBoolean;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property 8: A rejected save changes nothing.
 *
 * <p><b>Validates: Requirements 2.12, 3.7, 3.8</b>
 *
 * <p>A valid layout is saved first, then a second request that is invalid for exactly one reason
 * drawn from: unknown key, duplicate key, key restricted to other shop types, non-preset sticker
 * size, non-enum {@code blankValueBehavior}, non-boolean flag, or more keys than the size allows.
 * The second save must throw {@link ValidationException} and leave the stored document and the
 * repository's save count untouched.
 */
class LabelLayoutServiceRejectedSaveProperties {

  private static final FieldCatalog CATALOG = LabelLayoutServiceProperties.retailerCatalog();

  private static final List<String> AVAILABLE_KEYS =
      CATALOG.fields().stream()
          .filter(f -> f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  /** Keys that exist in the catalog but are not available for a RETAILER shop (e.g. ptr). */
  private static final List<String> RESTRICTED_KEYS =
      CATALOG.fields().stream()
          .filter(f -> !f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  private static final Set<String> ALL_KEYS =
      CATALOG.fields().stream().map(PrintableField::fieldKey).collect(Collectors.toSet());

  private static final Set<String> PRESET_SIZES =
      LabelLayoutDefaults.STICKER_SIZES.stream()
          .map(StickerSizeSpec::size)
          .collect(Collectors.toSet());

  private static final Set<String> ENUM_NAMES =
      Arrays.stream(BlankValueBehavior.values())
          .map(Enum::name)
          .collect(Collectors.toSet());

  record Scenario(
      String shopId,
      String userId,
      String secondUserId,
      SaveLabelLayoutRequest valid,
      SaveLabelLayoutRequest invalid,
      String reason) {}

  // Feature: barcode-label-layout, Property 8: A rejected save changes nothing
  @Property(tries = 100)
  void rejectedSaveLeavesTheStoredLayoutUntouched(@ForAll("scenarios") Scenario s) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = LabelLayoutServiceProperties.newService(repo);

    service.save(s.shopId(), s.userId(), s.valid());
    assertEquals(1, repo.size());
    LabelLayoutDocument before = repo.stored(s.shopId()).orElseThrow();
    int saveCallsBefore = repo.saveCalls();

    // Req 2.12 / 3.7 / 3.8: the invalid request is rejected ...
    assertThrows(
        ValidationException.class,
        () -> service.save(s.shopId(), s.secondUserId(), s.invalid()),
        () -> "expected rejection for reason '" + s.reason() + "'");

    // ... and nothing was written.
    assertEquals(saveCallsBefore, repo.saveCalls(), "repository.save must not be called");
    assertEquals(1, repo.size(), "no extra document may appear");
    LabelLayoutDocument after = repo.stored(s.shopId()).orElseThrow();
    assertEquals(before.getId(), after.getId());
    assertEquals(before.getShopId(), after.getShopId());
    assertEquals(before.getEnabledFieldKeys(), after.getEnabledFieldKeys());
    assertEquals(before.getStickerSize(), after.getStickerSize());
    assertEquals(before.getShowBarcodeText(), after.getShowBarcodeText());
    assertEquals(before.getShowFieldLabels(), after.getShowFieldLabels());
    assertEquals(before.getBlankValueBehavior(), after.getBlankValueBehavior());
    assertEquals(before.getUpdatedAt(), after.getUpdatedAt(), "updatedAt must not move");
    assertEquals(before.getUpdatedByUserId(), after.getUpdatedByUserId());
    assertEquals(s.userId(), after.getUpdatedByUserId(), "author stays the first saver");

    // The read path still reports the first save.
    assertEquals(
        before.getEnabledFieldKeys(),
        service.get(s.shopId()).enabledFields().stream().map(EnabledFieldDto::fieldKey).toList());
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<String> ids =
        Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('0', '9')
            .ofMinLength(1)
            .ofMaxLength(24);
    return Combinators.combine(
            ids.map(x -> "shop-" + x),
            ids.map(x -> "user-" + x),
            ids.map(x -> "user2-" + x),
            validRequests(),
            invalidRequests())
        .as(
            (shop, user, user2, valid, bad) ->
                new Scenario(shop, user, user2, valid, bad.get1(), bad.get2()));
  }

  /** Distinct RETAILER-available keys within the size cap, random (possibly omitted) options. */
  private Arbitrary<SaveLabelLayoutRequest> validRequests() {
    Arbitrary<String> size = Arbitraries.of(PRESET_SIZES).injectNull(0.3);
    Arbitrary<Boolean> flag = Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3);
    Arbitrary<String> blank = Arbitraries.of(ENUM_NAMES).injectNull(0.3);
    return Combinators.combine(size, flag, flag, blank)
        .flatAs(
            (sz, showCode, showLabels, behavior) ->
                keysUpTo(maxLines(sz))
                    .map(keys -> new SaveLabelLayoutRequest(keys, sz, showCode, showLabels, behavior)));
  }

  /** A request that is invalid for exactly one reason, tagged with that reason. */
  private Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> invalidRequests() {
    Arbitrary<String> size = Arbitraries.of(PRESET_SIZES);
    Arbitrary<Boolean> flag = Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3);
    Arbitrary<String> blank = Arbitraries.of(ENUM_NAMES).injectNull(0.3);

    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> unknownKey =
        Combinators.combine(size, flag, flag, blank)
            .flatAs(
                (sz, c, l, b) ->
                    Combinators.combine(keysUpTo(maxLines(sz) - 1), unknownKeys())
                        .as(
                            (keys, unknown) -> {
                              List<String> all = new ArrayList<>(keys);
                              all.add(unknown);
                              return Tuple.of(
                                  new SaveLabelLayoutRequest(all, sz, c, l, b), "unknown key");
                            }));

    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> duplicateKey =
        Combinators.combine(size, flag, flag, blank)
            .flatAs(
                (sz, c, l, b) ->
                    keysBetween(1, maxLines(sz) - 1)
                        .map(
                            keys -> {
                              List<String> all = new ArrayList<>(keys);
                              all.add(keys.get(0));
                              return Tuple.of(
                                  new SaveLabelLayoutRequest(all, sz, c, l, b), "duplicate key");
                            }));

    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> restrictedKey =
        Combinators.combine(size, flag, flag, blank)
            .flatAs(
                (sz, c, l, b) ->
                    Combinators.combine(keysUpTo(maxLines(sz) - 1), Arbitraries.of(RESTRICTED_KEYS))
                        .as(
                            (keys, restricted) -> {
                              List<String> all = new ArrayList<>(keys);
                              all.add(restricted);
                              return Tuple.of(
                                  new SaveLabelLayoutRequest(all, sz, c, l, b), "restricted key");
                            }));

    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> badSize =
        Combinators.combine(nonPresetSizes(), flag, flag, blank)
            .flatAs(
                (sz, c, l, b) ->
                    keysUpTo(1)
                        .map(
                            keys ->
                                Tuple.of(
                                    new SaveLabelLayoutRequest(keys, sz, c, l, b), "bad size")));

    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> badBlank =
        Combinators.combine(size, flag, flag, nonEnumBehaviors())
            .flatAs(
                (sz, c, l, b) ->
                    keysUpTo(maxLines(sz))
                        .map(
                            keys ->
                                Tuple.of(
                                    new SaveLabelLayoutRequest(keys, sz, c, l, b),
                                    "bad blankValueBehavior")));

    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> badFlag =
        Combinators.combine(size, Arbitraries.of(true, false), flag, blank)
            .flatAs(
                (sz, breakCode, other, b) ->
                    keysUpTo(maxLines(sz))
                        .map(
                            keys -> {
                              LenientBoolean bad = LenientBoolean.invalid("yes");
                              LenientBoolean ok = LenientBoolean.of(other);
                              SaveLabelLayoutRequest req =
                                  breakCode
                                      ? new SaveLabelLayoutRequest(keys, sz, bad, ok, b)
                                      : new SaveLabelLayoutRequest(keys, sz, ok, bad, b);
                              return Tuple.of(req, "non-boolean flag");
                            }));

    // Only sizes whose cap can actually be exceeded with the available keys.
    Arbitrary<String> exceedableSize = size.filter(sz -> maxLines(sz) < AVAILABLE_KEYS.size());
    Arbitrary<Tuple.Tuple2<SaveLabelLayoutRequest, String>> tooMany =
        Combinators.combine(exceedableSize, flag, flag, blank)
            .flatAs(
                (sz, c, l, b) ->
                    keysBetween(maxLines(sz) + 1, AVAILABLE_KEYS.size())
                        .map(
                            keys ->
                                Tuple.of(
                                    new SaveLabelLayoutRequest(keys, sz, c, l, b), "too many keys")));

    return Arbitraries.oneOf(
        unknownKey, duplicateKey, restrictedKey, badSize, badBlank, badFlag, tooMany);
  }

  private static int maxLines(String size) {
    return LabelLayoutDefaults.stickerSize(size)
        .orElseGet(LabelLayoutDefaults::defaultStickerSize)
        .maxLines();
  }

  private static Arbitrary<List<String>> keysUpTo(int max) {
    return keysBetween(0, max);
  }

  private static Arbitrary<List<String>> keysBetween(int min, int max) {
    int cappedMax = Math.min(max, AVAILABLE_KEYS.size());
    int cappedMin = Math.min(Math.max(min, 0), cappedMax);
    return Arbitraries.of(AVAILABLE_KEYS)
        .list()
        .uniqueElements()
        .ofMinSize(cappedMin)
        .ofMaxSize(cappedMax);
  }

  private static Arbitrary<String> unknownKeys() {
    return Arbitraries.strings()
        .withCharRange('a', 'z')
        .withCharRange('0', '9')
        .ofMinLength(1)
        .ofMaxLength(12)
        .filter(k -> !ALL_KEYS.contains(k));
  }

  private static Arbitrary<String> nonPresetSizes() {
    Arbitrary<String> nearMiss =
        Arbitraries.integers()
            .between(1, 200)
            .flatMap(w -> Arbitraries.integers().between(1, 200).map(h -> w + "x" + h));
    Arbitrary<String> other = Arbitraries.of("", " ", "50X25", "50x25mm", "A4", "small");
    return Arbitraries.oneOf(nearMiss, other).filter(sz -> !PRESET_SIZES.contains(sz));
  }

  private static Arbitrary<String> nonEnumBehaviors() {
    Arbitrary<String> variants =
        Arbitraries.of(ENUM_NAMES)
            .flatMap(n -> Arbitraries.of(n.toLowerCase(), n + "S", n.replace('_', '-')));
    Arbitrary<String> other = Arbitraries.of("", " ", "HIDE", "BLANK", "none");
    return Arbitraries.oneOf(variants, other).filter(b -> !ENUM_NAMES.contains(b.trim()));
  }
}
