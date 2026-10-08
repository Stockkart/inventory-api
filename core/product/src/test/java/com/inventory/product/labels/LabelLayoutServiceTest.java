package com.inventory.product.labels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.LabelLayoutDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.rest.dto.request.SaveLabelLayoutRequest;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.UserShopMembershipService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Example-based tests for {@link LabelLayoutService} and for the lenient boolean deserialization
 * path through {@link LabelLayoutValidator} (task 3.12; Req 2.2, 2.11, 3.6, 4.3, 4.4, 9.3).
 *
 * <p>Complements the jqwik properties in {@code LabelLayoutService*Properties}: these pin concrete
 * constants and the per-shop-type behaviour that is awkward to express as a generated property.
 */
class LabelLayoutServiceTest {

  private static final String SHOP = "shop-1";
  private static final String USER = "user-1";

  // ---- Default_Layout constants (Req 3.6) ---------------------------------------------------

  @Test
  void defaultLayoutHasTheDocumentedConstants() {
    LabelLayoutConfig def = LabelLayoutDefaults.defaultLayout();

    assertEquals(
        List.of(LabelFieldKeys.PRODUCT_NAME, LabelFieldKeys.COMPANY_NAME), def.enabledFieldKeys());
    assertEquals("50x25", def.stickerSize());
    assertEquals("50x25", LabelLayoutDefaults.DEFAULT_STICKER_SIZE);
    assertTrue(def.showBarcodeText());
    assertFalse(def.showFieldLabels());
    assertEquals(BlankValueBehavior.HIDE_LINE, def.blankValueBehavior());
  }

  // ---- get() with nothing saved (Req 2.2, 4.3) ---------------------------------------------

  @ParameterizedTest
  @EnumSource(ShopType.class)
  void getWithEmptyRepositoryReturnsDefaultLayoutForEveryShopType(ShopType shopType) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = serviceFor(repo, catalogFor(shopType));

    LabelLayoutResponse res = service.get(SHOP, USER);

    assertEquals(List.of("productName", "companyName"), keys(res));
    assertEquals("50x25", res.stickerSize());
    assertEquals(3, res.stickerSizeSpec().maxLines());
    assertTrue(res.showBarcodeText());
    assertFalse(res.showFieldLabels());
    assertEquals(BlankValueBehavior.HIDE_LINE, res.blankValueBehavior());
    assertTrue(res.isDefault());
    assertNull(res.updatedAt());
    assertNull(res.updatedByUserId());
    assertEquals(shopType, res.shopType());
    // Reads never persist (Req 2.2, 4.3).
    assertEquals(0, repo.size());
    assertEquals(0, repo.saveCalls());
  }

  // ---- shop type change leaves the stored document untouched (Req 4.4, 4.5) ----------------

  @Test
  void shopTypeChangeLeavesStoredDocumentUntouchedButFiltersResponse() {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelFieldCatalogService catalogService = mock(LabelFieldCatalogService.class);
    when(catalogService.catalog(anyString())).thenReturn(catalogFor(ShopType.DISTRIBUTOR));
    LabelLayoutService service = serviceFor(repo, catalogService);

    List<String> saved = List.of("productName", "ptr", "mrp");
    LabelLayoutResponse asDistributor =
        service.save(SHOP, USER, new SaveLabelLayoutRequest(saved, "50x25", true, false, null));
    assertEquals(saved, keys(asDistributor));
    LabelLayoutDocument storedBefore = repo.stored(SHOP).orElseThrow();
    assertEquals(saved, storedBefore.getEnabledFieldKeys());

    // The shop is now a RETAILER: ptr is no longer available to it.
    when(catalogService.catalog(anyString())).thenReturn(catalogFor(ShopType.RETAILER));
    LabelLayoutResponse asRetailer = service.get(SHOP, USER);

    // Req 4.5: the response omits the restricted field, order of the rest preserved.
    assertEquals(List.of("productName", "mrp"), keys(asRetailer));
    assertFalse(asRetailer.isDefault());
    assertEquals(ShopType.RETAILER, asRetailer.shopType());
    assertEquals(USER, asRetailer.updatedByUserId());
    assertEquals(storedBefore.getUpdatedAt(), asRetailer.updatedAt());

    // Req 4.4: the stored document is byte-for-byte what was saved; no rewrite happened.
    LabelLayoutDocument storedAfter = repo.stored(SHOP).orElseThrow();
    assertEquals(storedBefore.getId(), storedAfter.getId());
    assertEquals(saved, storedAfter.getEnabledFieldKeys());
    assertEquals(storedBefore.getStickerSize(), storedAfter.getStickerSize());
    assertEquals(storedBefore.getShowBarcodeText(), storedAfter.getShowBarcodeText());
    assertEquals(storedBefore.getShowFieldLabels(), storedAfter.getShowFieldLabels());
    assertEquals(storedBefore.getBlankValueBehavior(), storedAfter.getBlankValueBehavior());
    assertEquals(storedBefore.getUpdatedAt(), storedAfter.getUpdatedAt());
    assertEquals(storedBefore.getUpdatedByUserId(), storedAfter.getUpdatedByUserId());
    assertEquals(1, repo.size());
    assertEquals(1, repo.saveCalls());
  }

  // ---- only save() persists (Req 9.2, 9.3) --------------------------------------------------

  @Test
  void saveCreatesExactlyOneDocumentAndReadsCreateNone() {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = serviceFor(repo, catalogFor(ShopType.RETAILER));

    service.get(SHOP, USER);
    service.shopTypeDefaults(SHOP, USER);
    service.fieldCatalog(SHOP, USER);
    service.get(SHOP);
    service.shopTypeDefaults(SHOP);
    service.fieldCatalog(SHOP);
    assertEquals(0, repo.size(), "reads must not create documents");
    assertEquals(0, repo.saveCalls());

    LabelLayoutResponse res =
        service.save(
            SHOP,
            USER,
            new SaveLabelLayoutRequest(List.of("productName", "mrp"), "38x25", false, true, null));

    assertEquals(1, repo.size());
    assertEquals(1, repo.saveCalls());
    LabelLayoutDocument doc = repo.stored(SHOP).orElseThrow();
    assertNotNull(doc.getId());
    assertEquals(SHOP, doc.getShopId());
    assertEquals(USER, doc.getUpdatedByUserId());
    assertNotNull(doc.getUpdatedAt());
    assertFalse(res.isDefault());
    assertEquals(List.of("productName", "mrp"), keys(res));

    // Subsequent reads still don't add anything.
    service.get(SHOP, USER);
    service.shopTypeDefaults(SHOP, USER);
    service.fieldCatalog(SHOP, USER);
    assertEquals(1, repo.size());
    assertEquals(1, repo.saveCalls());
  }

  // ---- shopTypeDefaults (Req 4.1, 4.2) -------------------------------------------------------

  @ParameterizedTest
  @EnumSource(ShopType.class)
  void shopTypeDefaultsReturnTheDocumentedKeysAsDefault(ShopType shopType) {
    InMemoryLabelLayoutRepository repo = new InMemoryLabelLayoutRepository();
    LabelLayoutService service = serviceFor(repo, catalogFor(shopType));

    LabelLayoutResponse res = service.shopTypeDefaults(SHOP, USER);

    List<String> expected =
        shopType == ShopType.RETAILER
            ? List.of("productName", "companyName", "mrp")
            : List.of("productName", "companyName", "ptr", "mrp");
    assertEquals(expected, keys(res));
    assertTrue(res.isDefault());
    assertNull(res.updatedAt());
    assertNull(res.updatedByUserId());
    assertEquals(shopType, res.shopType());
    assertEquals("50x25", res.stickerSize());
    assertEquals(0, repo.size());
  }

  // ---- lenient boolean deserialization → validator (Req 2.11) --------------------------------

  @Test
  void jsonStringYesForShowBarcodeTextIsRejectedByName() throws Exception {
    SaveLabelLayoutRequest req =
        mapper().readValue("{\"showBarcodeText\":\"yes\"}", SaveLabelLayoutRequest.class);

    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () -> new LabelLayoutValidator().validate(req, catalogFor(ShopType.RETAILER)));

    assertTrue(
        ex.getMessage().contains("showBarcodeText must be true or false"), ex.getMessage());
    assertFalse(ex.getMessage().contains("showFieldLabels"), ex.getMessage());
  }

  @Test
  void jsonNumberOneForShowFieldLabelsIsRejectedByName() throws Exception {
    SaveLabelLayoutRequest req =
        mapper().readValue("{\"showFieldLabels\":1}", SaveLabelLayoutRequest.class);

    ValidationException ex =
        assertThrows(
            ValidationException.class,
            () -> new LabelLayoutValidator().validate(req, catalogFor(ShopType.RETAILER)));

    assertTrue(
        ex.getMessage().contains("showFieldLabels must be true or false"), ex.getMessage());
    assertFalse(ex.getMessage().contains("showBarcodeText"), ex.getMessage());
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static ObjectMapper mapper() {
    return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  private static List<String> keys(LabelLayoutResponse res) {
    return res.enabledFields().stream().map(EnabledFieldDto::fieldKey).toList();
  }

  /** The static field sets as a catalog whose effective shop type is {@code shopType}. */
  private static FieldCatalog catalogFor(ShopType shopType) {
    FieldCatalog retailer = LabelLayoutServiceProperties.retailerCatalog();
    return new FieldCatalog(
        retailer.fields(), retailer.stickerSizes(), shopType, true, List.of(), List.of());
  }

  private static LabelLayoutService serviceFor(
      InMemoryLabelLayoutRepository repo, FieldCatalog catalog) {
    LabelFieldCatalogService catalogService = mock(LabelFieldCatalogService.class);
    when(catalogService.catalog(anyString())).thenReturn(catalog);
    return serviceFor(repo, catalogService);
  }

  private static LabelLayoutService serviceFor(
      InMemoryLabelLayoutRepository repo, LabelFieldCatalogService catalogService) {
    UserShopMembershipService membership = mock(UserShopMembershipService.class);
    when(membership.hasAccess(anyString(), anyString())).thenReturn(true);
    return new LabelLayoutService(
        repo.repository(),
        catalogService,
        new LabelLayoutValidator(),
        membership,
        new ShopValidator());
  }
}
