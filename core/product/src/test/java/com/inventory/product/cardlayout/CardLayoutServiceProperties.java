package com.inventory.product.cardlayout;

// Feature: configurable-product-card, Property 4: Save/load round trip
// Feature: configurable-product-card, Property 5: Idempotent upsert
// Feature: configurable-product-card, Property 6: Reads never persist; rejected saves never change state

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.cards.CardField;
import com.inventory.pluginengine.cards.CardLayout;
import com.inventory.pluginengine.cards.CardRow;
import com.inventory.pluginengine.cards.CardSection;
import com.inventory.pluginengine.cards.CardVariant;
import com.inventory.pluginengine.cards.Emphasis;
import com.inventory.product.domain.model.CardLayoutDocument;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest;
import com.inventory.product.rest.dto.response.SurfaceLayoutResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties 4, 5 and 6 against {@link CardLayoutService} with an in-memory repository, a stubbed
 * catalog service (static catalog), a plugin registry with no plugins and a membership service that
 * always grants access.
 *
 * <p><b>Validates: Requirements 3.8, 4.3, 4.5, 4.6, 4.7</b>
 */
class CardLayoutServiceProperties {

  private static final String SHOP = "shop-1";
  private static final String USER = "user-1";
  private static final FieldCatalog CATALOG = CardLayoutTestFixtures.staticCatalog();

  record Harness(CardLayoutService service, InMemoryCardLayoutRepository repo) {}

  @Property(tries = 100)
  void saveThenLoadRoundTrips(@ForAll("cleanLayouts") CardLayout regular, @ForAll("cleanLayouts") CardLayout basic) {
    Harness h = harness();
    SaveCardLayoutRequest req =
        CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, regular, CardVariant.BASIC, basic));

    h.service().save(SHOP, USER, CardSurfaceIds.PRODUCT_SEARCH, req);
    SurfaceLayoutResponse loaded = h.service().get(SHOP, USER, CardSurfaceIds.PRODUCT_SEARCH);

    assertThat(loaded.isDefault()).isFalse();
    assertThat(loaded.updatedAt()).isNotNull();
    assertThat(loaded.updatedByUserId()).isEqualTo(USER);
    assertThat(specOf(loaded.variants().get(CardVariant.REGULAR))).isEqualTo(specOf(regular));
    assertThat(specOf(loaded.variants().get(CardVariant.BASIC))).isEqualTo(specOf(basic));
    // the stored document carries exactly what was saved
    CardLayout stored =
        CardLayoutDocuments.toLayouts(h.repo().stored(SHOP, CardSurfaceIds.PRODUCT_SEARCH).orElseThrow())
            .get(CardVariant.REGULAR);
    assertThat(stored).isEqualTo(regular);
  }

  @Property(tries = 60)
  void omittedVariantIsFilledWithDefault(@ForAll("cleanLayouts") CardLayout regular) {
    Harness h = harness();
    // scan-sell excludes `description`; the generator may have drawn it, so strip it first
    CardLayout allowed = without(regular, "description");
    h.service().save(SHOP, USER, CardSurfaceIds.SCAN_SELL, CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, allowed)));

    CardLayout storedBasic =
        CardLayoutDocuments.toLayouts(h.repo().stored(SHOP, CardSurfaceIds.SCAN_SELL).orElseThrow()).get(CardVariant.BASIC);
    assertThat(storedBasic).isEqualTo(CardLayoutDefaults.scanSell());
  }

  @Property(tries = 60)
  void repeatedSavesKeepOneDocumentEqualToTheLast(@ForAll("layoutSequences") List<CardLayout> sequence) {
    Harness h = harness();
    CardLayout last = null;
    for (CardLayout l : sequence) {
      h.service().save(SHOP, USER, CardSurfaceIds.PRODUCT_SEARCH, CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, l)));
      last = l;
    }
    if (last == null) {
      assertThat(h.repo().size()).isZero();
      return;
    }
    assertThat(h.repo().size()).isEqualTo(1);
    CardLayoutDocument doc = h.repo().stored(SHOP, CardSurfaceIds.PRODUCT_SEARCH).orElseThrow();
    assertThat(CardLayoutDocuments.toLayouts(doc).get(CardVariant.REGULAR)).isEqualTo(last);
  }

  @Property(tries = 60)
  void readsNeverPersistAndRejectedSavesLeaveStateUnchanged(@ForAll("cleanLayouts") CardLayout seed) {
    Harness h = harness();
    // start from a saved state so "unchanged" is observable
    h.service().save(SHOP, USER, CardSurfaceIds.PRODUCT_SEARCH, CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, seed)));
    Map<String, CardLayoutDocument> before = h.repo().snapshot();
    int savesBefore = h.repo().saveCalls();

    h.service().getAll(SHOP, USER);
    h.service().get(SHOP, USER, CardSurfaceIds.SCAN_SELL);
    h.service().defaults(SHOP, USER, CardSurfaceIds.PRODUCT_SEARCH);
    h.service().fieldCatalog(SHOP, USER);

    CardLayout bad =
        CardLayout.of(
            CardLayoutDefaults.DEFAULT_OPTIONS,
            CardSection.of("x", CardRow.of(new CardField("nope", true, null, Emphasis.NORMAL))));
    assertThatThrownBy(
            () -> h.service().save(SHOP, USER, CardSurfaceIds.PRODUCT_SEARCH, CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, bad))))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(
            () -> h.service().save(SHOP, USER, "no-such-surface", CardLayoutTestFixtures.request(Map.of(CardVariant.REGULAR, seed))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Unknown card surface");

    assertThat(h.repo().saveCalls()).isEqualTo(savesBefore);
    assertThat(h.repo().snapshot()).isEqualTo(before);
  }

  @Property(tries = 20)
  void unsavedSurfacesServeDefaults(@ForAll("cleanLayouts") CardLayout ignored) {
    Harness h = harness();
    var all = h.service().getAll(SHOP, USER);
    assertThat(all.surfaces()).extracting(SurfaceLayoutResponse::surfaceId)
        .containsExactly(CardSurfaceIds.PRODUCT_SEARCH, CardSurfaceIds.SCAN_SELL);
    all.surfaces().forEach(s -> {
      assertThat(s.isDefault()).isTrue();
      assertThat(s.updatedAt()).isNull();
      assertThat(s.variants().keySet()).containsExactlyInAnyOrder(CardVariant.REGULAR, CardVariant.BASIC);
    });
    assertThat(h.repo().size()).isZero();
  }

  // ---- fixtures ------------------------------------------------------------------------------

  @Provide
  Arbitrary<CardLayout> cleanLayouts() {
    List<String> pool = CardLayoutTestFixtures.cardKeys(CATALOG);
    return Arbitraries.of(pool)
        .list()
        .uniqueElements()
        .ofMaxSize(12)
        .map(
            keys -> {
              List<CardSection> sections = new ArrayList<>();
              for (int i = 0; i < keys.size(); i += 3) {
                List<CardField> fields = new ArrayList<>();
                for (int j = i; j < Math.min(i + 3, keys.size()); j++) {
                  fields.add(new CardField(keys.get(j), j % 2 == 0, j % 3 == 0 ? "L" + j : null, Emphasis.values()[j % 3]));
                }
                sections.add(new CardSection("s" + i, i % 2 == 0 ? null : "T" + i, i % 2 == 1, List.of(new CardRow(fields))));
              }
              return new CardLayout(sections, CardLayoutDefaults.DEFAULT_OPTIONS);
            });
  }

  @Provide
  Arbitrary<List<CardLayout>> layoutSequences() {
    return cleanLayouts().list().ofMaxSize(5);
  }

  private static CardLayout without(CardLayout l, String key) {
    List<CardSection> sections = new ArrayList<>();
    for (CardSection s : l.sections()) {
      List<CardRow> rows = new ArrayList<>();
      for (CardRow r : s.rows()) {
        List<CardField> fields = r.fields().stream().filter(f -> !f.fieldKey().equals(key)).toList();
        if (!fields.isEmpty()) {
          rows.add(new CardRow(fields));
        }
      }
      if (!rows.isEmpty()) {
        sections.add(new CardSection(s.id(), s.title(), s.dividerAbove(), rows));
      }
    }
    return new CardLayout(sections, l.options());
  }

  private static Harness harness() {
    InMemoryCardLayoutRepository repo = new InMemoryCardLayoutRepository();
    ShopRepository shops = mock(ShopRepository.class);
    Shop shop = new Shop();
    shop.setShopId(SHOP);
    shop.setShopType(ShopType.RETAILER);
    when(shops.findById(SHOP)).thenReturn(Optional.of(shop));
    LabelFieldCatalogService catalogService = mock(LabelFieldCatalogService.class);
    when(catalogService.catalog(any(Shop.class))).thenReturn(CATALOG);
    UserShopMembershipService membership = mock(UserShopMembershipService.class);
    when(membership.hasAccess(anyString(), anyString())).thenReturn(true);
    CardLayoutService service =
        new CardLayoutService(
            repo.asRepository(),
            shops,
            catalogService,
            new CardSurfaceRegistry(new PluginRegistry(List.of())),
            new CardLayoutValidator(),
            new CardLayoutResolver(),
            membership,
            new ShopValidator());
    return new Harness(service, repo);
  }

  /** The persisted-shape view of a resolved or stored layout, for round-trip comparison. */
  private static List<String> specOf(ResolvedCardLayout r) {
    List<String> out = new ArrayList<>();
    r.sections().forEach(s -> {
      out.add("S:" + s.id() + ":" + s.title() + ":" + s.dividerAbove());
      s.rows().forEach(row -> row.fields().forEach(f -> out.add(
          "F:" + f.fieldKey() + ":" + f.showLabel() + ":" + f.emphasis() + ":" + overrideOf(f))));
    });
    out.add("O:" + r.options());
    return out;
  }

  private static List<String> specOf(CardLayout l) {
    List<String> out = new ArrayList<>();
    l.sections().forEach(s -> {
      out.add("S:" + s.id() + ":" + s.title() + ":" + s.dividerAbove());
      s.rows().forEach(row -> row.fields().forEach(f -> out.add(
          "F:" + f.fieldKey() + ":" + f.showLabel() + ":" + f.emphasis() + ":" + f.labelOverride())));
    });
    out.add("O:" + l.options());
    return out;
  }

  /** A resolved field's label equals the override when one was set, else the catalog label. */
  private static String overrideOf(ResolvedCardLayout.Field f) {
    String catalogLabel = CATALOG.find(f.fieldKey()).map(c -> c.label()).orElse(null);
    return f.label().equals(catalogLabel) ? null : f.label();
  }

  @SuppressWarnings("unused")
  private static Set<String> keys(CardLayout l) {
    return new HashSet<>(CardLayoutTestFixtures.keys(l));
  }
}
