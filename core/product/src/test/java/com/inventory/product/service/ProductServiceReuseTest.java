package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.validation.ProductValidator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Stock-in reuses the shop's existing catalog product whenever the entry describes the same item,
 * so the same medicine does not pile up as several product documents.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceReuseTest {

  private static final String SHOP = "shop-1";
  private static final String BARCODE = "8901082053712";

  @Mock private ProductRepository productRepository;
  @Mock private BarcodeService barcodeService;
  @Spy private ProductValidator productValidator = new ProductValidator();

  @InjectMocks private ProductService productService;

  @BeforeEach
  void stubSave() {
    Mockito.lenient()
        .when(productRepository.save(any(Product.class)))
        .thenAnswer(
            inv -> {
              Product p = inv.getArgument(0);
              if (p.getId() == null) {
                p.setId("new-product");
              }
              return p;
            });
  }

  @Test
  void aPickedHistoryProductWithTheOldTypeSpellingIsReused() {
    Product history = product("history", null);
    history.setBusinessType("PHARMACEUTICAL");
    history.setItemType(null);
    when(productRepository.findByIdAndShopId("history", SHOP)).thenReturn(Optional.of(history));

    String id = productService.resolveForRegistration("history", entry(null), SHOP);

    assertEquals("history", id);
    assertEquals("MEDICAL", history.getBusinessType());
    assertEquals(ItemType.NORMAL, history.getItemType());
    verify(productRepository).save(history);
  }

  @Test
  void anEntryWithoutAPickedProductMatchesAHistoryProduct() {
    Product history = product("history", null);
    history.setBusinessType("PHARMACY");
    history.setItemType(null);
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(history));

    String id = productService.resolveForRegistration(null, entry(null), SHOP);

    assertEquals("history", id);
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void businessTypeCaseDoesNotSplitAProduct() {
    Product existing = product("sports", null);
    existing.setBusinessType("sports");
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(existing));
    Inventory entry = entry(null);
    entry.setBusinessType("SPORTS");

    String id = productService.resolveForRegistration(null, entry, SHOP);

    assertEquals("sports", id);
  }

  @Test
  void anEntryWithoutABarcodeReusesTheProductThatHasOne() {
    Product existing = product("with-code", BARCODE);
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(existing));

    String id = productService.resolveForRegistration(null, entry(null), SHOP);

    assertEquals("with-code", id);
    assertEquals(BARCODE, existing.getBarcode());
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void aBarcodeClearedOnAPickedProductIsClearedInPlace() {
    Product existing = product("with-code", BARCODE);
    when(productRepository.findByIdAndShopId("with-code", SHOP)).thenReturn(Optional.of(existing));

    String id = productService.resolveForRegistration("with-code", entry(null), SHOP, true);

    assertEquals("with-code", id);
    assertEquals(null, existing.getBarcode());
    verify(productRepository).save(existing);
  }

  @Test
  void aStockInLineWithoutABarcodeKeepsThePickedProductsBarcode() {
    Product existing = product("with-code", BARCODE);
    when(productRepository.findByIdAndShopId("with-code", SHOP)).thenReturn(Optional.of(existing));

    String id = productService.resolveForRegistration("with-code", entry(null), SHOP);

    assertEquals("with-code", id);
    assertEquals(BARCODE, existing.getBarcode());
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void aLineWithoutAnItemTypeDoesNotRewriteThePickedProduct() {
    Product existing = product("normal", BARCODE);
    when(productRepository.findByIdAndShopId("normal", SHOP)).thenReturn(Optional.of(existing));
    Inventory entry = entry(BARCODE);
    entry.setItemType(null);

    productService.resolveForRegistration("normal", entry, SHOP);

    assertEquals(ItemType.NORMAL, existing.getItemType());
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void anOldBusinessTypeSpellingOnTheLineDoesNotDowngradeThePickedProduct() {
    Product existing = product("medical", BARCODE);
    when(productRepository.findByIdAndShopId("medical", SHOP)).thenReturn(Optional.of(existing));
    Inventory entry = entry(BARCODE);
    entry.setBusinessType("PHARMACEUTICAL");

    productService.resolveForRegistration("medical", entry, SHOP);

    assertEquals("MEDICAL", existing.getBusinessType());
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void aNewBarcodeOnAPickedProductReusesTheCopyThatAlreadyHasIt() {
    Product picked = product("picked", "8901082053736");
    Product sibling = product("sibling", BARCODE);
    when(productRepository.findByIdAndShopId("picked", SHOP)).thenReturn(Optional.of(picked));
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(picked, sibling));

    String id = productService.resolveForRegistration("picked", entry(BARCODE), SHOP);

    assertEquals("sibling", id);
    assertEquals("8901082053736", picked.getBarcode());
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void aCompanySpellingCorrectedOnAPickedProductIsSavedInPlace() {
    Product existing = product("atta", null);
    existing.setCompanyName("ATTA  GHAR");
    when(productRepository.findByIdAndShopId("atta", SHOP)).thenReturn(Optional.of(existing));
    Inventory entry = entry(null);
    entry.setCompanyName("ATTA GHAR");

    String id = productService.resolveForRegistration("atta", entry, SHOP);

    assertEquals("atta", id);
    assertEquals("ATTA GHAR", existing.getCompanyName());
    verify(productRepository).save(existing);
  }

  @Test
  void aPickedProductTheEntryLeavesUnchangedIsNotWritten() {
    Product existing = product("same", BARCODE);
    when(productRepository.findByIdAndShopId("same", SHOP)).thenReturn(Optional.of(existing));

    String id = productService.resolveForRegistration("same", entry(BARCODE), SHOP);

    assertEquals("same", id);
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void anEntryBarcodeIsGivenToTheProductThatHadNone() {
    Product existing = product("no-code", null);
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(existing));

    String id = productService.resolveForRegistration(null, entry(BARCODE), SHOP);

    assertEquals("no-code", id);
    assertEquals(BARCODE, existing.getBarcode());
    verify(productRepository).save(existing);
    verify(barcodeService).claimPoolForProduct(SHOP, "no-code", BARCODE);
  }

  @Test
  void theCopyWithTheSameBarcodeWinsOverABlankCopy() {
    Product blank = product("blank", null);
    Product coded = product("coded", BARCODE);
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(blank, coded));

    String id = productService.resolveForRegistration(null, entry(BARCODE), SHOP);

    assertEquals("coded", id);
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void aBlankEntryPrefersTheCopyThatHasABarcode() {
    Product blank = product("blank", null);
    blank.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
    Product coded = product("coded", BARCODE);
    coded.setCreatedAt(Instant.parse("2026-09-24T00:00:00Z"));
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(blank, coded));

    String id = productService.resolveForRegistration(null, entry(null), SHOP);

    assertEquals("coded", id);
  }

  @Test
  void twoDifferentBarcodesStillMeanTwoProducts() {
    Product existing = product("other-code", "8901082053736");
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(existing));

    String id = productService.resolveForRegistration(null, entry(BARCODE), SHOP);

    assertEquals("new-product", id);
    assertEquals("8901082053736", existing.getBarcode());
  }

  @Test
  void companySpacingAndCaseDoNotSplitAProduct() {
    Product existing = product("atta", null);
    existing.setCompanyName("ATTA  GHAR ");
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(existing));
    Inventory entry = entry(null);
    entry.setCompanyName("Atta Ghar");

    String id = productService.resolveForRegistration(null, entry, SHOP);

    assertEquals("atta", id);
  }

  @Test
  void aPickedProductThatNoLongerMatchesReusesAnotherCopyBeforeCreatingOne() {
    Product picked = product("picked", null);
    picked.setHsn("3004");
    Product sibling = product("sibling", null);
    when(productRepository.findByIdAndShopId("picked", SHOP)).thenReturn(Optional.of(picked));
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(picked, sibling));

    String id = productService.resolveForRegistration("picked", entry(null), SHOP);

    assertEquals("sibling", id);
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void aDifferentHsnIsStillADifferentProduct() {
    Product existing = product("other-hsn", null);
    existing.setHsn("3004");
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 200ml 1x36"))
        .thenReturn(List.of(existing));

    String id = productService.resolveForRegistration(null, entry(null), SHOP);

    assertEquals("new-product", id);
  }

  private static Product product(String id, String barcode) {
    Product p = new Product();
    p.setId(id);
    p.setShopId(SHOP);
    p.setName("M2TONE 200ML 1X36");
    p.setNormalizedName("m2tone 200ml 1x36");
    p.setCompanyName("CHARAK PHARMA PVT. LTD.");
    p.setBarcode(barcode);
    p.setBusinessType("MEDICAL");
    p.setItemType(ItemType.NORMAL);
    p.setBaseUnit("PCS");
    p.setHsn("30049011");
    return p;
  }

  private static Inventory entry(String barcode) {
    Inventory inv = new Inventory();
    inv.setName("M2TONE 200ML 1X36");
    inv.setCompanyName("CHARAK PHARMA PVT. LTD.");
    inv.setBarcode(barcode);
    inv.setBusinessType("MEDICAL");
    inv.setItemType(ItemType.NORMAL);
    inv.setBaseUnit("PCS");
    inv.setHsn("30049011");
    return inv;
  }
}
