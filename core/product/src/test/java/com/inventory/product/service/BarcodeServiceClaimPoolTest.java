package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.product.domain.model.BarcodePool;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import com.inventory.product.domain.repository.BarcodePoolRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.validation.ProductValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link BarcodeService#claimPoolForProduct}: saving a product on Product Entry with a pool code
 * marks it ATTACHED and fills the pool row's label from the label source product, the same way
 * the Attach button does.
 */
@ExtendWith(MockitoExtension.class)
class BarcodeServiceClaimPoolTest {

  private static final String SHOP = "shop-1";
  private static final String CODE = "SKFD4FPMQ9XLJ2";

  @Mock private BarcodePoolRepository barcodePoolRepository;
  @Mock private ProductRepository productRepository;
  @Spy private ProductValidator productValidator = new ProductValidator();
  @InjectMocks private BarcodeService barcodeService;

  @Test
  void productEntryClaimAttachesTheCodeAndFillsTheLabel() {
    BarcodePool pool = pool(BarcodePoolStatus.UNUSED, null);
    when(barcodePoolRepository.findByShopIdAndCode(SHOP, CODE)).thenReturn(Optional.of(pool));
    when(productRepository.findByIdAndShopId("p1", SHOP))
        .thenReturn(Optional.of(product("p1", "bar-1", "sports king")));

    barcodeService.claimPoolForProduct(SHOP, "p1", CODE);

    ArgumentCaptor<BarcodePool> saved = ArgumentCaptor.forClass(BarcodePool.class);
    verify(barcodePoolRepository).save(saved.capture());
    assertEquals(BarcodePoolStatus.ATTACHED, saved.getValue().getStatus());
    assertEquals("p1", saved.getValue().getProductId());
    assertEquals("bar-1", saved.getValue().getLabelName());
    assertEquals("sports king", saved.getValue().getLabelCompany());
  }

  @Test
  void blankLabelOnAnAttachedCodeIsFilledFromTheFirstAttachedProduct() {
    BarcodePool pool = pool(BarcodePoolStatus.ATTACHED, "p1");
    when(barcodePoolRepository.findByShopIdAndCode(SHOP, CODE)).thenReturn(Optional.of(pool));
    when(productRepository.findByIdAndShopId("p1", SHOP))
        .thenReturn(Optional.of(product("p1", "bar-1", "sports king")));

    // A second product sharing the code is saved; the first product stays the label source.
    barcodeService.claimPoolForProduct(SHOP, "p2", CODE);

    assertEquals("p1", pool.getProductId());
    assertEquals("bar-1", pool.getLabelName());
    assertEquals("sports king", pool.getLabelCompany());
    verify(barcodePoolRepository).save(pool);
  }

  @Test
  void existingLabelTextIsKept() {
    BarcodePool pool = pool(BarcodePoolStatus.UNUSED, null);
    pool.setLabelName("Printed name");
    when(barcodePoolRepository.findByShopIdAndCode(SHOP, CODE)).thenReturn(Optional.of(pool));
    when(productRepository.findByIdAndShopId("p1", SHOP))
        .thenReturn(Optional.of(product("p1", "bar-1", "sports king")));

    barcodeService.claimPoolForProduct(SHOP, "p1", CODE);

    assertEquals("Printed name", pool.getLabelName());
    assertEquals("sports king", pool.getLabelCompany());
  }

  @Test
  void attachedCodeWithACompleteLabelIsLeftAlone() {
    BarcodePool pool = pool(BarcodePoolStatus.ATTACHED, "p1");
    pool.setLabelName("bar-1");
    pool.setLabelCompany("sports king");
    when(barcodePoolRepository.findByShopIdAndCode(SHOP, CODE)).thenReturn(Optional.of(pool));

    barcodeService.claimPoolForProduct(SHOP, "p2", CODE);

    verify(barcodePoolRepository, never()).save(any());
    verify(productRepository, never()).findByIdAndShopId(any(), any());
  }

  @Test
  void codeNotInThePoolIsIgnored() {
    when(barcodePoolRepository.findByShopIdAndCode(SHOP, "OTHER")).thenReturn(Optional.empty());

    barcodeService.claimPoolForProduct(SHOP, "p1", "OTHER");

    verify(barcodePoolRepository, never()).save(any());
  }

  private static BarcodePool pool(BarcodePoolStatus status, String productId) {
    BarcodePool pool = new BarcodePool();
    pool.setShopId(SHOP);
    pool.setCode(CODE);
    pool.setStatus(status);
    pool.setProductId(productId);
    return pool;
  }

  private static Product product(String id, String name, String company) {
    Product product = new Product();
    product.setId(id);
    product.setName(name);
    product.setCompanyName(company);
    return product;
  }
}
