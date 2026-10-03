// Feature: barcode-label-layout, Property 16: Reads never persist
// Feature: barcode-label-layout, Property 17: Shop-type defaults are fixed and never persisted
package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 16: Reads never persist.
 *
 * <p><b>Validates: Requirements 2.2, 4.3, 9.2</b>
 *
 * <p>Property 17: Shop-type defaults are fixed and never persisted.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.6</b>
 *
 * <p>Both properties run the real {@link LabelLayoutService} against {@link
 * InMemoryLabelLayoutRepository}, a static-field-set catalog for the shop type under test, a
 * membership service that always grants access and the real {@link LabelLayoutValidator}. No
 * layout is ever saved, so every read must come back as the Default_Layout (or the shop-type
 * defaults) and the repository must stay empty.
 */
class LabelLayoutServiceReadsProperties {

  private static final String SHOP_ID = "shop-reads";
  private static final String USER_ID = "user-reads";

  private static final List<String> DEFAULT_KEYS = List.of("productName", "companyName");
  private static final List<String> RETAILER_DEFAULT_KEYS =
      List.of("productName", "companyName", "mrp");
  private static final List<String> TRADE_DEFAULT_KEYS =
      List.of("productName", "companyName", "ptr", "mrp");

  /** Read-only operations exposed by the service. */
  enum ReadOp {
    GET,
    GET_WITH_USER,
    SHOP_TYPE_DEFAULTS,
    FIELD_CATALOG,
    RESOLVED_CONFIG,
    EFFECTIVE_LAYOUT
  }

  // Feature: barcode-label-layout, Property 16: Reads never persist
  @Property(tries = 100)
  void anySequenceOfReadsNeverPersistsAndKeepsReturningDefaults(
      @ForAll("readSequences") List<ReadOp> ops) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    FieldCatalog catalog = LabelLayoutServiceProperties.retailerCatalog();
    LabelLayoutService service = LabelLayoutServiceProperties.newService(repo);

    for (ReadOp op : ops) {
      switch (op) {
        case GET -> assertDefaultLayout(service.get(SHOP_ID));
        case GET_WITH_USER -> assertDefaultLayout(service.get(SHOP_ID, USER_ID));
        case SHOP_TYPE_DEFAULTS -> service.shopTypeDefaults(SHOP_ID);
        case FIELD_CATALOG -> service.fieldCatalog(SHOP_ID);
        case RESOLVED_CONFIG ->
            assertEquals(LabelLayoutDefaults.defaultLayout(), service.resolvedConfig(SHOP_ID));
        case EFFECTIVE_LAYOUT -> service.effectiveLayout(SHOP_ID, catalog);
      }
      // Req 2.2 / 4.3 / 9.2: reads never write.
      assertEquals(0, repo.saveCalls(), "a read operation invoked repository.save: " + op);
      assertEquals(0, repo.size(), "a read operation stored a document: " + op);
    }

    // After the whole sequence, get() still reports the Default_Layout.
    assertDefaultLayout(service.get(SHOP_ID));
    assertEquals(0, repo.saveCalls());
    assertEquals(0, repo.size());
  }

  // Feature: barcode-label-layout, Property 17: Shop-type defaults are fixed and never persisted
  @Property(tries = 100)
  void shopTypeDefaultsAreFixedPerShopTypeAndNeverPersisted(
      @ForAll("shopTypes") ShopType shopType, @ForAll("callCounts") int calls) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = newService(repo, catalogFor(shopType));
    List<String> expectedKeys =
        shopType == ShopType.RETAILER ? RETAILER_DEFAULT_KEYS : TRADE_DEFAULT_KEYS;

    for (int i = 0; i < calls; i++) {
      LabelLayoutResponse defaults = service.shopTypeDefaults(SHOP_ID);
      assertEquals(expectedKeys, keysOf(defaults), "enabled keys for " + shopType);
      assertDefaultOptions(defaults);
      assertTrue(defaults.isDefault(), "shop-type defaults must report isDefault=true");
      assertNull(defaults.updatedAt());
      assertNull(defaults.updatedByUserId());
      assertEquals(shopType, defaults.shopType());
      assertEquals(0, repo.saveCalls(), "shopTypeDefaults invoked repository.save");
      assertEquals(0, repo.size(), "shopTypeDefaults stored a document");
    }

    // Req 4.6: null / unknown shop type falls back to the RETAILER defaults.
    assertEquals(
        LabelLayoutDefaults.shopTypeDefaults(ShopType.RETAILER),
        LabelLayoutDefaults.shopTypeDefaults(null));
    assertEquals(RETAILER_DEFAULT_KEYS, LabelLayoutDefaults.shopTypeDefaults(null).enabledFieldKeys());
  }

  // ---- assertions ----------------------------------------------------------------------------

  private static void assertDefaultLayout(LabelLayoutResponse r) {
    assertEquals(DEFAULT_KEYS, keysOf(r), "Default_Layout keys");
    assertDefaultOptions(r);
    assertTrue(r.isDefault(), "unsaved layout must report isDefault=true");
    assertNull(r.updatedAt(), "unsaved layout has no updatedAt");
    assertNull(r.updatedByUserId(), "unsaved layout has no updatedByUserId");
  }

  private static void assertDefaultOptions(LabelLayoutResponse r) {
    LabelLayoutConfig d = LabelLayoutDefaults.defaultLayout();
    assertEquals(d.stickerSize(), r.stickerSize());
    assertEquals("50x25", r.stickerSizeSpec().size());
    assertEquals(50, r.stickerSizeSpec().widthMm());
    assertEquals(25, r.stickerSizeSpec().heightMm());
    assertEquals(3, r.stickerSizeSpec().maxLines());
    assertEquals(d.showBarcodeText(), r.showBarcodeText());
    assertEquals(d.showFieldLabels(), r.showFieldLabels());
    assertEquals(d.blankValueBehavior(), r.blankValueBehavior());
  }

  private static List<String> keysOf(LabelLayoutResponse r) {
    return r.enabledFields().stream().map(EnabledFieldDto::fieldKey).toList();
  }

  // ---- generators ----------------------------------------------------------------------------

  @Provide
  Arbitrary<List<ReadOp>> readSequences() {
    return Arbitraries.of(ReadOp.class).list().ofMinSize(1).ofMaxSize(8);
  }

  @Provide
  Arbitrary<ShopType> shopTypes() {
    return Arbitraries.of(ShopType.RETAILER, ShopType.DISTRIBUTOR, ShopType.WHOLESALER);
  }

  @Provide
  Arbitrary<Integer> callCounts() {
    return Arbitraries.integers().between(1, 5);
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static LabelLayoutService newService(
      InMemoryLabelLayoutRepository repo, FieldCatalog catalog) {
    LabelFieldCatalogService catalogService = mock(LabelFieldCatalogService.class);
    when(catalogService.catalog(anyString())).thenReturn(catalog);
    UserShopMembershipService membership = mock(UserShopMembershipService.class);
    when(membership.hasAccess(anyString(), anyString())).thenReturn(true);
    return new LabelLayoutService(
        repo.repository(),
        catalogService,
        new LabelLayoutValidator(),
        membership,
        new ShopValidator());
  }

  /** Static field sets (no vertical fields) for the given shop type. */
  private static FieldCatalog catalogFor(ShopType shopType) {
    List<PrintableField> fields = new ArrayList<>();
    fields.addAll(LabelLayoutDefaults.coreFields());
    fields.addAll(LabelLayoutDefaults.pricingFields());
    fields.addAll(LabelLayoutDefaults.lotFields());
    fields.addAll(LabelLayoutDefaults.shopFields());
    return new FieldCatalog(
        fields, LabelLayoutDefaults.STICKER_SIZES, shopType, true, List.of(), List.of());
  }
}
