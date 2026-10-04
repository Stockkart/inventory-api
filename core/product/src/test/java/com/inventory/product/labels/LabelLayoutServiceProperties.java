// Feature: barcode-label-layout, Property 6: Save then load round-trips, with omitted options filled from Default_Layout
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 6: Save then load round-trips, with omitted options filled from Default_Layout.
 *
 * <p><b>Validates: Requirements 2.1, 2.3, 2.8, 3.9</b>
 *
 * <p>The service runs against {@link InMemoryLabelLayoutRepository}, a RETAILER catalog built from
 * the static field sets, a membership service that always grants access and the real {@link
 * LabelLayoutValidator}. Every generated request is valid: distinct RETAILER-available keys whose
 * count fits the chosen (or default) sticker size.
 */
class LabelLayoutServiceProperties {

  private static final FieldCatalog CATALOG = retailerCatalog();

  private static final List<String> AVAILABLE_KEYS =
      CATALOG.fields().stream()
          .filter(f -> f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  /** A fully specified or partially omitted save request. */
  record Scenario(String shopId, String userId, SaveLabelLayoutRequest request) {}

  // Feature: barcode-label-layout, Property 6: Save then load round-trips, with omitted options filled from Default_Layout
  @Property(tries = 100)
  void saveThenGetReturnsTheSavedLayoutWithDefaultsFilledIn(@ForAll("scenarios") Scenario s) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = newService(repo);
    LabelLayoutConfig defaults = LabelLayoutDefaults.defaultLayout();
    SaveLabelLayoutRequest req = s.request();

    Instant before = Instant.now();
    LabelLayoutResponse saved = service.save(s.shopId(), s.userId(), req);
    Instant after = Instant.now();
    LabelLayoutResponse loaded = service.get(s.shopId());

    // Req 2.1 / 2.3: the saved layout is what comes back on read.
    assertEquals(saved, loaded, "get() must return exactly what save() returned");

    List<String> loadedKeys = loaded.enabledFields().stream().map(EnabledFieldDto::fieldKey).toList();
    assertEquals(req.enabledFieldKeys(), loadedKeys, "enabled field order must round-trip");

    // Req 2.8 / 3.9: omitted options come from the Default_Layout.
    String expectedSize = req.stickerSize() == null ? defaults.stickerSize() : req.stickerSize();
    assertEquals(expectedSize, loaded.stickerSize());
    assertEquals(expectedSize, loaded.stickerSizeSpec().size());

    boolean expectedShowCode =
        req.showBarcodeTextValue() == null ? defaults.showBarcodeText() : req.showBarcodeTextValue();
    assertEquals(expectedShowCode, loaded.showBarcodeText());

    boolean expectedShowLabels =
        req.showFieldLabelsValue() == null ? defaults.showFieldLabels() : req.showFieldLabelsValue();
    assertEquals(expectedShowLabels, loaded.showFieldLabels());

    BlankValueBehavior expectedBlank =
        req.blankValueBehavior() == null
            ? defaults.blankValueBehavior()
            : BlankValueBehavior.valueOf(req.blankValueBehavior());
    assertEquals(expectedBlank, loaded.blankValueBehavior());

    // Persistence metadata.
    assertFalse(loaded.isDefault(), "a saved layout is never reported as default");
    assertEquals(s.userId(), loaded.updatedByUserId());
    assertNotNull(loaded.updatedAt());
    assertFalse(loaded.updatedAt().isBefore(before), "updatedAt before save started");
    assertFalse(loaded.updatedAt().isAfter(after), "updatedAt after save finished");

    // The stored document is keyed by the context shopId and there is exactly one.
    assertEquals(1, repo.size());
    LabelLayoutDocument doc = repo.stored(s.shopId()).orElseThrow();
    assertEquals(s.shopId(), doc.getShopId());
    assertNotNull(doc.getId());
    assertEquals(req.enabledFieldKeys(), doc.getEnabledFieldKeys());
    assertEquals(expectedSize, doc.getStickerSize());
    assertEquals(expectedShowCode, doc.getShowBarcodeText());
    assertEquals(expectedShowLabels, doc.getShowFieldLabels());
    assertEquals(expectedBlank.name(), doc.getBlankValueBehavior());
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<String> ids =
        Arbitraries.strings().withCharRange('a', 'z').withCharRange('0', '9').ofMinLength(1).ofMaxLength(24);
    Arbitrary<String> stickerSize =
        Arbitraries.of(LabelLayoutDefaults.STICKER_SIZES.stream().map(StickerSizeSpec::size).toList())
            .injectNull(0.3);
    Arbitrary<Boolean> flag = Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3);
    Arbitrary<String> blank =
        Arbitraries.of(BlankValueBehavior.values()).map(Enum::name).injectNull(0.3);

    Arbitrary<SaveLabelLayoutRequest> requests =
        Combinators.combine(stickerSize, flag, flag, blank)
            .flatAs(
                (size, showCode, showLabels, blankBehavior) -> {
                  int maxLines =
                      LabelLayoutDefaults.stickerSize(size)
                          .orElseGet(LabelLayoutDefaults::defaultStickerSize)
                          .maxLines();
                  return Arbitraries.of(AVAILABLE_KEYS)
                      .list()
                      .uniqueElements()
                      .ofMinSize(0)
                      .ofMaxSize(maxLines)
                      .map(
                          keys ->
                              new SaveLabelLayoutRequest(
                                  keys, size, showCode, showLabels, blankBehavior));
                });

    return Combinators.combine(ids.map(s -> "shop-" + s), ids.map(s -> "user-" + s), requests)
        .as(Scenario::new);
  }

  // ---- fixtures ------------------------------------------------------------------------------

  static LabelLayoutService newService(InMemoryLabelLayoutRepository repo) {
    LabelFieldCatalogService catalogService = mock(LabelFieldCatalogService.class);
    when(catalogService.catalog(anyString())).thenReturn(CATALOG);
    UserShopMembershipService membership = mock(UserShopMembershipService.class);
    when(membership.hasAccess(anyString(), anyString())).thenReturn(true);
    return new LabelLayoutService(
        repo.repository(),
        catalogService,
        new LabelLayoutValidator(),
        membership,
        new ShopValidator());
  }

  static FieldCatalog retailerCatalog() {
    List<PrintableField> fields = new ArrayList<>();
    fields.addAll(LabelLayoutDefaults.coreFields());
    fields.addAll(LabelLayoutDefaults.pricingFields());
    fields.addAll(LabelLayoutDefaults.lotFields());
    fields.addAll(LabelLayoutDefaults.shopFields());
    return new FieldCatalog(
        fields, LabelLayoutDefaults.STICKER_SIZES, ShopType.RETAILER, true, List.of(), List.of());
  }
}
