// Feature: barcode-label-layout, Property 7: Repeated saves keep one document equal to the last save
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 7: Repeated saves keep one document equal to the last save.
 *
 * <p><b>Validates: Requirements 2.9</b>
 *
 * <p>For any non-empty sequence of valid save requests for one shop (possibly with identical
 * consecutive requests and varying users), the repository holds exactly one document for that shop
 * afterwards, its id is the one assigned by the first save, and its content equals the normalized
 * last request. Reuses the fixtures from {@link LabelLayoutServiceProperties}.
 */
class LabelLayoutServiceRepeatedSaveProperties {

  private static final FieldCatalog CATALOG = LabelLayoutServiceProperties.retailerCatalog();

  private static final List<String> AVAILABLE_KEYS =
      CATALOG.fields().stream()
          .filter(f -> f.isAvailableFor(ShopType.RETAILER))
          .map(PrintableField::fieldKey)
          .toList();

  /** One save in the sequence: who saved, and what. */
  record Save(String userId, SaveLabelLayoutRequest request) {}

  record Scenario(String shopId, List<Save> saves) {}

  // Feature: barcode-label-layout, Property 7: Repeated saves keep one document equal to the last save
  @Property(tries = 100)
  void repeatedSavesKeepOneDocumentEqualToTheLastSave(@ForAll("scenarios") Scenario s) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = LabelLayoutServiceProperties.newService(repo);
    LabelLayoutConfig defaults = LabelLayoutDefaults.defaultLayout();

    String firstId = null;
    LabelLayoutResponse lastResponse = null;
    for (Save save : s.saves()) {
      lastResponse = service.save(s.shopId(), save.userId(), save.request());
      assertEquals(1, repo.size(), "every save must leave exactly one document");
      String id = repo.stored(s.shopId()).orElseThrow().getId();
      assertNotNull(id);
      if (firstId == null) {
        firstId = id;
      } else {
        assertEquals(firstId, id, "document id must be stable across saves");
      }
    }

    Save last = s.saves().get(s.saves().size() - 1);
    SaveLabelLayoutRequest req = last.request();

    // Exactly one document for the shop, with the id assigned on the first save.
    assertEquals(1, repo.size());
    assertEquals(s.saves().size(), repo.saveCalls(), "one repository save per request");
    LabelLayoutDocument doc = repo.stored(s.shopId()).orElseThrow();
    assertEquals(firstId, doc.getId());
    assertEquals(s.shopId(), doc.getShopId());

    // Content equals the normalized last request (nulls -> Default_Layout).
    assertEquals(req.enabledFieldKeys(), doc.getEnabledFieldKeys());
    String expectedSize = req.stickerSize() == null ? defaults.stickerSize() : req.stickerSize();
    assertEquals(expectedSize, doc.getStickerSize());
    boolean expectedShowCode =
        req.showBarcodeTextValue() == null ? defaults.showBarcodeText() : req.showBarcodeTextValue();
    assertEquals(expectedShowCode, doc.getShowBarcodeText());
    boolean expectedShowLabels =
        req.showFieldLabelsValue() == null ? defaults.showFieldLabels() : req.showFieldLabelsValue();
    assertEquals(expectedShowLabels, doc.getShowFieldLabels());
    BlankValueBehavior expectedBlank =
        req.blankValueBehavior() == null
            ? defaults.blankValueBehavior()
            : BlankValueBehavior.valueOf(req.blankValueBehavior());
    assertEquals(expectedBlank.name(), doc.getBlankValueBehavior());
    assertEquals(last.userId(), doc.getUpdatedByUserId());

    // Reading back yields the last save's response.
    assertEquals(lastResponse, service.get(s.shopId()));
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<String> ids =
        Arbitraries.strings().withCharRange('a', 'z').withCharRange('0', '9').ofMinLength(1).ofMaxLength(24);
    Arbitrary<String> userIds = ids.map(u -> "user-" + u);

    // A non-empty sequence of valid requests; each step either repeats the previous request
    // verbatim or draws a fresh one, so identical consecutive saves are exercised too.
    Arbitrary<List<Save>> saves =
        Arbitraries.integers()
            .between(1, 6)
            .flatMap(
                n ->
                    Combinators.combine(
                            validRequests().list().ofSize(n),
                            userIds.list().ofSize(n),
                            Arbitraries.of(Boolean.TRUE, Boolean.FALSE).list().ofSize(n))
                        .as(
                            (requests, users, repeatFlags) -> {
                              List<Save> out = new ArrayList<>(n);
                              SaveLabelLayoutRequest previous = null;
                              for (int i = 0; i < n; i++) {
                                SaveLabelLayoutRequest r =
                                    (previous != null && repeatFlags.get(i)) ? previous : requests.get(i);
                                out.add(new Save(users.get(i), r));
                                previous = r;
                              }
                              return out;
                            }));

    return Combinators.combine(ids.map(x -> "shop-" + x), saves).as(Scenario::new);
  }

  private static Arbitrary<SaveLabelLayoutRequest> validRequests() {
    Arbitrary<String> stickerSize =
        Arbitraries.of(LabelLayoutDefaults.STICKER_SIZES.stream().map(StickerSizeSpec::size).toList())
            .injectNull(0.3);
    Arbitrary<Boolean> flag = Arbitraries.of(Boolean.TRUE, Boolean.FALSE).injectNull(0.3);
    Arbitrary<String> blank =
        Arbitraries.of(BlankValueBehavior.values()).map(Enum::name).injectNull(0.3);

    return Combinators.combine(stickerSize, flag, flag, blank)
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
                          new SaveLabelLayoutRequest(keys, size, showCode, showLabels, blankBehavior));
            });
  }
}
