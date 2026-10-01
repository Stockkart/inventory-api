package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.rest.dto.request.AmendVendorPurchaseInvoiceRequest;
import com.inventory.product.validation.VendorPurchaseInvoiceValidator;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VendorPurchaseInvoiceServiceAmendTest {

  @Mock private VendorPurchaseInvoiceRepository vendorPurchaseInvoiceRepository;
  @Mock private VendorRepository vendorRepository;
  @Mock private VendorPurchaseInvoiceValidator vendorPurchaseInvoiceValidator;
  @Mock private PurchaseTaxRecorder purchaseTaxRecorder;
  @Mock private InventoryService inventoryService;
  @InjectMocks private VendorPurchaseInvoiceService service;

  private final VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();

  @BeforeEach
  void setUp() {
    invoice.setId("inv-1");
    invoice.setShopId("s1");
    invoice.setLineSubTotal(new BigDecimal("1100.00"));
    invoice.setTaxTotal(new BigDecimal("50.00"));
    invoice.setLines(List.of());
    when(vendorPurchaseInvoiceRepository.findById("inv-1")).thenReturn(Optional.of(invoice));
    when(vendorPurchaseInvoiceRepository.findByIdAndShopId("inv-1", "s1"))
        .thenReturn(Optional.of(invoice));
    when(vendorPurchaseInvoiceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
  }

  private static AmendVendorPurchaseInvoiceRequest amend(String subTotal, String reason) {
    AmendVendorPurchaseInvoiceRequest request = new AmendVendorPurchaseInvoiceRequest();
    request.setLineSubTotal(new BigDecimal(subTotal));
    request.setReason(reason);
    return request;
  }

  @Test
  void validatesRecordsAndRepostsInThatOrder() {
    service.amendHeader("inv-1", "s1", "u1", amend("1000.00", "subtotal was gross"));

    InOrder order = inOrder(vendorPurchaseInvoiceValidator, purchaseTaxRecorder, inventoryService);
    order.verify(vendorPurchaseInvoiceValidator).validateHeaderAmounts(
        eq(new BigDecimal("1000.00")), eq(new BigDecimal("50.00")), any(), any(), any(), any());
    order.verify(purchaseTaxRecorder).record(invoice);
    order.verify(inventoryService)
        .repostAccountingAfterAmend(invoice, "s1", "u1", "subtotal was gross");
    assertEquals(0, new BigDecimal("1100.00").compareTo(invoice.getPreviousHeader().getLineSubTotal()));
  }

  @Test
  void aRejectedHeaderChangesNothingDownstream() {
    doThrow(new ValidationException("Line subtotal cannot be negative"))
        .when(vendorPurchaseInvoiceValidator)
        .validateHeaderAmounts(any(), any(), any(), any(), any(), any());

    assertThrows(ValidationException.class,
        () -> service.amendHeader("inv-1", "s1", "u1", amend("-1", "typo")));

    verify(purchaseTaxRecorder, never()).record(any());
    verify(inventoryService, never()).repostAccountingAfterAmend(any(), any(), any(), any());
    verify(vendorPurchaseInvoiceRepository, never()).save(any());
  }

  @Test
  void aReasonIsRequired() {
    assertThrows(ValidationException.class,
        () -> service.amendHeader("inv-1", "s1", "u1", amend("1000.00", " ")));
  }
}
