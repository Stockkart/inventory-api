package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.domain.repository.ShopRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

  private static final String PURCHASE_ID = "purchase-1";
  private static final String SHOP_ID = "shop-1";
  private static final String OTHER_SHOP_ID = "shop-2";

  @Mock private PurchaseRepository purchaseRepository;
  @Mock private ShopRepository shopRepository;
  @Mock private InvoiceSettingsService invoiceSettingsService;
  @Mock private DocumentService documentService;

  private InvoiceService service;

  @BeforeEach
  void setUp() {
    service = new InvoiceService();
    ReflectionTestUtils.setField(service, "purchaseRepository", purchaseRepository);
    ReflectionTestUtils.setField(service, "shopRepository", shopRepository);
    ReflectionTestUtils.setField(service, "invoiceSettingsService", invoiceSettingsService);
    ReflectionTestUtils.setField(service, "documentService", documentService);
  }

  // --- generateInvoiceText -------------------------------------------------

  @Test
  void generateInvoiceText_unknownPurchase_throwsResourceNotFound() {
    when(purchaseRepository.findById(PURCHASE_ID)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.generateInvoiceText(PURCHASE_ID, SHOP_ID));
  }

  @Test
  void generateInvoiceText_purchaseBelongsToDifferentShop_throwsValidation() {
    Purchase purchase = new Purchase();
    purchase.setId(PURCHASE_ID);
    purchase.setShopId(OTHER_SHOP_ID);
    when(purchaseRepository.findById(PURCHASE_ID)).thenReturn(Optional.of(purchase));

    assertThrows(
        ValidationException.class, () -> service.generateInvoiceText(PURCHASE_ID, SHOP_ID));
  }

  @Test
  void generateInvoiceText_shopMissing_throwsResourceNotFound() {
    Purchase purchase = new Purchase();
    purchase.setId(PURCHASE_ID);
    purchase.setShopId(SHOP_ID);
    when(purchaseRepository.findById(PURCHASE_ID)).thenReturn(Optional.of(purchase));
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.generateInvoiceText(PURCHASE_ID, SHOP_ID));
  }

  @Test
  void generateInvoiceText_happyPath_delegatesToDocumentServiceWithDotMatrixPrinterType() {
    Purchase purchase = new Purchase();
    purchase.setId(PURCHASE_ID);
    purchase.setShopId(SHOP_ID);
    Shop shop = new Shop();
    shop.setShopId(SHOP_ID);
    shop.setName("Test Shop");
    when(purchaseRepository.findById(PURCHASE_ID)).thenReturn(Optional.of(purchase));
    when(shopRepository.findById(SHOP_ID)).thenReturn(Optional.of(shop));
    when(invoiceSettingsService.getOrDefaultForShop(SHOP_ID))
        .thenReturn(new ShopInvoiceSettingsDocument());
    when(documentService.generateInvoiceText(any(GenerateInvoiceRequest.class)))
        .thenReturn("INVOICE TEXT\n");

    String text = service.generateInvoiceText(PURCHASE_ID, SHOP_ID);

    assertEquals("INVOICE TEXT\n", text);
    ArgumentCaptor<GenerateInvoiceRequest> captor =
        ArgumentCaptor.forClass(GenerateInvoiceRequest.class);
    verify(documentService).generateInvoiceText(captor.capture());
    assertEquals("DOT_MATRIX", captor.getValue().getPrinterType());
  }

  // --- generateInvoicePdf: shared lookup/validation branch ------------------

  @Test
  void generateInvoicePdf_unknownPurchase_throwsResourceNotFound() {
    when(purchaseRepository.findById(PURCHASE_ID)).thenReturn(Optional.empty());

    assertThrows(
        ResourceNotFoundException.class,
        () -> service.generateInvoicePdf(PURCHASE_ID, SHOP_ID, null));
  }

  @Test
  void generateInvoicePdf_purchaseBelongsToDifferentShop_throwsValidation() {
    Purchase purchase = new Purchase();
    purchase.setId(PURCHASE_ID);
    purchase.setShopId(OTHER_SHOP_ID);
    when(purchaseRepository.findById(PURCHASE_ID)).thenReturn(Optional.of(purchase));

    assertThrows(
        ValidationException.class, () -> service.generateInvoicePdf(PURCHASE_ID, SHOP_ID, null));
  }
}
