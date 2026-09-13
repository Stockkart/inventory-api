package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.validation.ProductValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A product edit is not a stock-in. Registration asks "which product did this delivery bring in?",
 * so a known barcode wins and the name on the vendor's bill is ignored. An edit asks the opposite
 * question -- someone changed the name on purpose -- and these tests pin that difference down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductServiceResolveForEditTest {

  private static final String SHOP = "shop-1";
  private static final String COMPANY = "CHARAK PHARMA PVT LTD.";
  private static final String HSN = "30049011";

  @Mock private ProductRepository productRepository;
  @Mock private BarcodeService barcodeService;

  private ProductService service;

  @BeforeEach
  void setUp() {
    service = new ProductService();
    ReflectionTestUtils.setField(service, "productRepository", productRepository);
    ReflectionTestUtils.setField(service, "productValidator", new ProductValidator());
    ReflectionTestUtils.setField(service, "barcodeService", barcodeService);
    when(productRepository.findByShopIdAndNormalizedName(anyString(), anyString()))
        .thenReturn(List.of());
    when(productRepository.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
  }

  private Product product(String id, String name, String barcode) {
    Product p = new Product();
    p.setId(id);
    p.setShopId(SHOP);
    p.setName(name);
    p.setNormalizedName(name.toLowerCase());
    p.setCompanyName(COMPANY);
    p.setBarcode(barcode);
    p.setHsn(HSN);
    p.setBaseUnit("MLT");
    return p;
  }

  private Inventory lotRenamedTo(String newName, String barcode) {
    Inventory inv = new Inventory();
    inv.setShopId(SHOP);
    inv.setName(newName);
    inv.setCompanyName(COMPANY);
    inv.setBarcode(barcode);
    inv.setHsn(HSN);
    inv.setBaseUnit("MLT");
    return inv;
  }

  /**
   * The M2TONE case: the lot's product carries a barcode, so registration returns the barcode owner
   * before it ever compares the name and the rename is dropped with no error.
   */
  @Test
  void renamingABarcodedProductWritesTheNewNameToTheCatalog() {
    Product existing = product("prod-1", "M2TONE", "8901082053712");
    when(productRepository.findByIdAndShopId("prod-1", SHOP)).thenReturn(Optional.of(existing));
    when(productRepository.findByShopIdAndBarcode(SHOP, "8901082053712"))
        .thenReturn(Optional.of(existing));

    String resolved =
        service.resolveForEdit("prod-1", lotRenamedTo("M2TONE 200ML 1X36", "8901082053712"), SHOP);

    assertEquals("prod-1", resolved, "an edit must keep the same catalog product");
    ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(saved.capture());
    assertEquals("M2TONE 200ML 1X36", saved.getValue().getName());
    assertEquals("m2tone 200ml 1x36", saved.getValue().getNormalizedName());
  }

  /**
   * Without a barcode the old path forked a new product and re-pointed only the edited lot, leaving
   * every other lot of that product on the old name.
   */
  @Test
  void renamingAnUnbarcodedProductUpdatesItInsteadOfForking() {
    Product existing = product("prod-2", "BONNISON SYRUP 200 ML", null);
    when(productRepository.findByIdAndShopId("prod-2", SHOP)).thenReturn(Optional.of(existing));

    String resolved =
        service.resolveForEdit("prod-2", lotRenamedTo("BONNISAN SYRUP 200 ML", null), SHOP);

    assertEquals("prod-2", resolved, "a rename must not fork a second product");
    ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(saved.capture());
    assertSame(existing, saved.getValue());
    assertEquals("BONNISAN SYRUP 200 ML", saved.getValue().getName());
  }

  /** Renaming onto a name the shop already stocks should join that product, not duplicate it. */
  @Test
  void renamingOntoAnExistingProductAdoptsItRatherThanCreatingATwin() {
    Product existing = product("prod-3", "M2TONE", null);
    Product twin = product("prod-twin", "M2TONE 450ML 1X24", null);
    when(productRepository.findByIdAndShopId("prod-3", SHOP)).thenReturn(Optional.of(existing));
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "m2tone 450ml 1x24"))
        .thenReturn(List.of(twin));

    String resolved =
        service.resolveForEdit("prod-3", lotRenamedTo("M2TONE 450ML 1X24", null), SHOP);

    assertEquals("prod-twin", resolved, "the lot should join the product that already matches");
    verify(productRepository, never()).save(any(Product.class));
  }

  /**
   * Guard on the behaviour we are deliberately not changing: a stock-in of a known barcode keeps
   * reusing that product, so a vendor's spelling never rewrites the catalog.
   */
  @Test
  void stockInOfAKnownBarcodeStillIgnoresTheNameOnTheVendorsBill() {
    Product existing = product("prod-4", "GUMTONE POWDER 40GM 1X96", "8901082054054");
    when(productRepository.findByShopIdAndBarcode(SHOP, "8901082054054"))
        .thenReturn(Optional.of(existing));

    String resolved =
        service.resolveForRegistration(
            "prod-4", lotRenamedTo("GUMTONE P040", "8901082054054"), SHOP);

    assertEquals("prod-4", resolved);
    verify(productRepository, never()).save(any(Product.class));
  }
}
