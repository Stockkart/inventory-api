package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.domain.repository.StockEntryEstimateRepository;
import com.inventory.product.validation.ProductValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ProductService#retireIfUnused}: renaming a lot moves it to another product; the old one
 * is deleted only when no lot and no estimate line still uses it, so a shared barcode never prints
 * a second sticker from a product nothing points at.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceRetireTest {

  private static final String SHOP = "shop-1";
  private static final String CODE = "SK3667H0RWNVPE";

  @Mock private ProductRepository productRepository;
  @Mock private InventoryRepository inventoryRepository;
  @Mock private StockEntryEstimateRepository stockEntryEstimateRepository;
  @Mock private BarcodeService barcodeService;
  @Spy private ProductValidator productValidator = new ProductValidator();

  @InjectMocks private ProductService productService;

  @Test
  void anOldProductNothingUsesIsDeletedAndItsPoolRowMovesToTheNewOne() {
    Product old = product("old", "LEGGING ELASTANO N (S)");
    Product renamed = product("new", "LEGGINGS ELASTANO N (S)");
    when(productRepository.findByIdAndShopId("old", SHOP)).thenReturn(Optional.of(old));
    when(productRepository.findByIdAndShopId("new", SHOP)).thenReturn(Optional.of(renamed));

    assertTrue(productService.retireIfUnused("old", "new", SHOP));

    InOrder order = inOrder(barcodeService, productRepository);
    order.verify(barcodeService).movePoolToProduct(SHOP, "old", renamed);
    order.verify(productRepository).delete(old);
  }

  @Test
  void anOldProductAnotherLotStillUsesIsKept() {
    when(productRepository.findByIdAndShopId("old", SHOP))
        .thenReturn(Optional.of(product("old", "LEGGING ELASTANO N (S)")));
    when(inventoryRepository.existsByShopIdAndProductId(SHOP, "old")).thenReturn(true);

    assertFalse(productService.retireIfUnused("old", "new", SHOP));

    verify(productRepository, never()).delete(any(Product.class));
    verifyNoInteractions(barcodeService);
  }

  @Test
  void anOldProductAnEstimateLineStillNamesIsKept() {
    when(productRepository.findByIdAndShopId("old", SHOP))
        .thenReturn(Optional.of(product("old", "LEGGING ELASTANO N (S)")));
    when(stockEntryEstimateRepository.existsByShopIdAndLinesProductId(SHOP, "old"))
        .thenReturn(true);

    assertFalse(productService.retireIfUnused("old", "new", SHOP));

    verify(productRepository, never()).delete(any(Product.class));
    verifyNoInteractions(barcodeService);
  }

  @Test
  void aLotThatKeptItsProductDeletesNothing() {
    assertFalse(productService.retireIfUnused("same", "same", SHOP));
    assertFalse(productService.retireIfUnused(null, "new", SHOP));

    verifyNoInteractions(productRepository, inventoryRepository, barcodeService);
  }

  @Test
  void anOldProductAlreadyGoneDeletesNothing() {
    when(productRepository.findByIdAndShopId(anyString(), any())).thenReturn(Optional.empty());

    assertFalse(productService.retireIfUnused("old", "new", SHOP));

    verify(productRepository, never()).delete(any(Product.class));
    verifyNoInteractions(barcodeService);
  }

  private static Product product(String id, String name) {
    Product p = new Product();
    p.setId(id);
    p.setShopId(SHOP);
    p.setName(name);
    p.setBarcode(CODE);
    return p;
  }
}
