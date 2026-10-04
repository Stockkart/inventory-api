package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.rest.dto.request.AmendVendorPurchaseInvoiceRequest;
import com.inventory.product.rest.dto.response.AmendInvoicePreviewResponse;
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

  private static AmendVendorPurchaseInvoiceRequest amend(String discount, String reason) {
    AmendVendorPurchaseInvoiceRequest request = new AmendVendorPurchaseInvoiceRequest();
    request.setOverallDiscount(new BigDecimal(discount));
    request.setReason(reason);
    return request;
  }

  @Test
  void validatesRecordsAndRepostsInThatOrder() {
    service.amendHeader("inv-1", "s1", "u1", amend("100.00", "discount was missed"));

    InOrder order = inOrder(vendorPurchaseInvoiceValidator, purchaseTaxRecorder, inventoryService);
    order.verify(vendorPurchaseInvoiceValidator).validateHeaderAmounts(
        any(), any(), eq(new BigDecimal("100.00")));
    order.verify(purchaseTaxRecorder).record(invoice);
    order.verify(inventoryService)
        .repostAccountingAfterAmend(invoice, "s1", "u1", "discount was missed");
    assertEquals(0, new BigDecimal("1100.00").compareTo(invoice.getPreviousHeader().getLineSubTotal()));
  }

  @Test
  void aRejectedHeaderChangesNothingDownstream() {
    doThrow(new ValidationException("Overall discount cannot be negative"))
        .when(vendorPurchaseInvoiceValidator)
        .validateHeaderAmounts(any(), any(), any());

    assertThrows(ValidationException.class,
        () -> service.amendHeader("inv-1", "s1", "u1", amend("-1", "typo")));

    verify(purchaseTaxRecorder, never()).record(any());
    verify(inventoryService, never()).repostAccountingAfterAmend(any(), any(), any(), any());
    verify(vendorPurchaseInvoiceRepository, never()).save(any());
  }

  @Test
  void aReasonIsRequired() {
    assertThrows(ValidationException.class,
        () -> service.amendHeader("inv-1", "s1", "u1", amend("100.00", " ")));
  }

  /** Recalculating gives back the totals the bill already has, as the real recorder would. */
  private void recorderRestoresTheSavedTotals() {
    doAnswer(call -> {
      VendorPurchaseInvoice inv = call.getArgument(0);
      inv.setLineSubTotal(new BigDecimal("1100.00"));
      inv.setTaxTotal(new BigDecimal("50.00"));
      inv.setInvoiceTotal(new BigDecimal("1150.00"));
      return null;
    }).when(purchaseTaxRecorder).record(any());
    invoice.setInvoiceTotal(new BigDecimal("1150.00"));
  }

  @Test
  void thePreviewShowsWhatWouldMoveAndSavesNothing() {
    recorderRestoresTheSavedTotals();
    AmendVendorPurchaseInvoiceRequest request = amend("100.00", "discount was missed");

    AmendInvoicePreviewResponse preview = service.previewAmendment("inv-1", "s1", request);

    assertTrue(preview.getChangedFields().contains("overallDiscount"));
    assertTrue(preview.isJournalReposted());
    assertEquals(0, new BigDecimal("100.00").compareTo(preview.getCorrected().getOverallDiscount()));
    // The saved invoice is untouched and nothing is written or reposted.
    assertEquals(null, invoice.getOverallDiscount());
    verify(vendorPurchaseInvoiceRepository, never()).save(any());
    verify(inventoryService, never()).repostAccountingAfterAmend(any(), any(), any(), any());
  }

  @Test
  void aCorrectionThatChangesNothingIsRefused() {
    recorderRestoresTheSavedTotals();
    invoice.setOverallDiscount(new BigDecimal("100.00"));

    ValidationException e = assertThrows(ValidationException.class,
        () -> service.amendHeader("inv-1", "s1", "u1", amend("100", "same again")));

    assertTrue(e.getMessage().contains("Nothing would change"), e.getMessage());
    verify(inventoryService, never()).repostAccountingAfterAmend(any(), any(), any(), any());
    verify(vendorPurchaseInvoiceRepository, never()).save(any());
  }
}
