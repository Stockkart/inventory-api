package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.BarcodePool;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import com.inventory.product.domain.repository.BarcodePoolRepository;
import com.inventory.product.validation.ProductValidator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BarcodeService#movePoolToProduct}: before a renamed lot's old product is deleted, its pool
 * rows go to the new product, or back to UNUSED when the new product carries another code.
 */
@ExtendWith(MockitoExtension.class)
class BarcodeServiceMovePoolTest {

  private static final String SHOP = "shop-1";
  private static final String CODE = "SK3667H0RWNVPE";

  @Mock private BarcodePoolRepository barcodePoolRepository;
  @Spy private ProductValidator productValidator = new ProductValidator();
  @InjectMocks private BarcodeService barcodeService;

  @Test
  void aRowWithTheNewProductsCodeIsAttachedToItWithItsStickerText() {
    BarcodePool row = row(CODE);
    when(barcodePoolRepository.findAllByShopIdAndProductId(SHOP, "old")).thenReturn(List.of(row));

    barcodeService.movePoolToProduct(SHOP, "old", product("new", CODE));

    verify(barcodePoolRepository).save(row);
    assertEquals(BarcodePoolStatus.ATTACHED, row.getStatus());
    assertEquals("new", row.getProductId());
    assertEquals("LEGGINGS ELASTANO N (S)", row.getLabelName());
    assertEquals("SPORTS KING", row.getLabelCompany());
  }

  @Test
  void aRowWithACodeNoProductHoldsAnyMoreGoesBackToUnused() {
    BarcodePool row = row(CODE);
    when(barcodePoolRepository.findAllByShopIdAndProductId(SHOP, "old")).thenReturn(List.of(row));

    barcodeService.movePoolToProduct(SHOP, "old", product("new", "SK3667OTHERCODE"));

    verify(barcodePoolRepository).save(row);
    assertEquals(BarcodePoolStatus.UNUSED, row.getStatus());
    assertNull(row.getProductId());
  }

  private static BarcodePool row(String code) {
    BarcodePool pool = new BarcodePool();
    pool.setShopId(SHOP);
    pool.setCode(code);
    pool.setStatus(BarcodePoolStatus.ATTACHED);
    pool.setProductId("old");
    pool.setLabelName("LEGGING ELASTANO N (S)");
    return pool;
  }

  private static Product product(String id, String barcode) {
    Product p = new Product();
    p.setId(id);
    p.setShopId(SHOP);
    p.setName("LEGGINGS ELASTANO N (S)");
    p.setCompanyName("SPORTS KING");
    p.setBarcode(barcode);
    return p;
  }
}
