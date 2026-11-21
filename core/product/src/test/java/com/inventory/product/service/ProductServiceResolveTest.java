package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.validation.ProductValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductServiceResolveTest {

  private static final String SHOP = "shop-1";
  private static final String BARCODE = "8901234567890";

  @Mock private ProductRepository productRepository;
  @Mock private BarcodeService barcodeService;
  @Spy private ProductValidator productValidator = new ProductValidator();

  @InjectMocks private ProductService productService;

  @BeforeEach
  void stubSave() {
    org.mockito.Mockito.lenient()
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
  void sameBarcodeWithDifferentPackFactorCreatesNewProduct() {
    Product existing = product("prod-20", BARCODE, 20);
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "omefish"))
        .thenReturn(List.of(existing));

    String id = productService.resolveForRegistration(null, lot(BARCODE, 10), SHOP);

    assertEquals("new-product", id);
    ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(saved.capture());
    assertEquals(BARCODE, saved.getValue().getBarcode());
    assertEquals(10, saved.getValue().getUnitConversions().getFactor());
  }

  @Test
  void identicalIdentityReusesProduct() {
    Product existing = product("prod-20", BARCODE, 20);
    when(productRepository.findByShopIdAndNormalizedName(SHOP, "omefish"))
        .thenReturn(List.of(existing));

    String id = productService.resolveForRegistration(null, lot(BARCODE, 20), SHOP);

    assertEquals("prod-20", id);
    verify(productRepository, never()).save(any(Product.class));
  }

  @Test
  void onlyBarcodeChangedUpdatesInPlaceEvenWhenCodeIsShared() {
    Product existing = product("prod-20", "OLD-CODE", 20);
    when(productRepository.findByIdAndShopId("prod-20", SHOP)).thenReturn(Optional.of(existing));

    String id = productService.resolveForRegistration("prod-20", lot(BARCODE, 20), SHOP);

    assertEquals("prod-20", id);
    assertEquals(BARCODE, existing.getBarcode());
    verify(productRepository, never()).findAllByShopIdAndBarcode(anyString(), anyString());
    verify(barcodeService).claimPoolForProduct(SHOP, "prod-20", BARCODE);
  }

  @Test
  void packFactorChangeOnExistingProductForks() {
    Product existing = product("prod-20", BARCODE, 20);
    when(productRepository.findByIdAndShopId("prod-20", SHOP)).thenReturn(Optional.of(existing));

    String id = productService.resolveForRegistration("prod-20", lot(BARCODE, 10), SHOP);

    assertNotEquals("prod-20", id);
    assertEquals(20, existing.getUnitConversions().getFactor());
  }

  private static Product product(String id, String barcode, int factor) {
    Product p = new Product();
    p.setId(id);
    p.setShopId(SHOP);
    p.setName("Omefish");
    p.setNormalizedName("omefish");
    p.setCompanyName("Acme");
    p.setBarcode(barcode);
    p.setBaseUnit("PCS");
    p.setUnitConversions(new UnitConversion("PAC", factor));
    return p;
  }

  private static Inventory lot(String barcode, int factor) {
    Inventory inv = new Inventory();
    inv.setName("Omefish");
    inv.setCompanyName("Acme");
    inv.setBarcode(barcode);
    inv.setBaseUnit("PCS");
    inv.setUnitConversions(new UnitConversion("PAC", factor));
    return inv;
  }
}
