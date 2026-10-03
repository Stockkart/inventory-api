package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.VendorPurchaseInvoicePostingRequest;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceAmendRepostTest {

  @Mock private AccountingFacade accountingFacade;
  @Mock private VendorRepository vendorRepository;
  @Mock private ShopRepository shopRepository;
  @Mock private PurchaseTaxRecorder purchaseTaxRecorder;
  @InjectMocks private InventoryService inventoryService;

  private final VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();

  @BeforeEach
  void setUp() {
    invoice.setId("inv-1");
    invoice.setShopId("s1");
    invoice.setVendorId("v1");
    invoice.setInvoiceNo("A011767");
    invoice.setLineSubTotal(new BigDecimal("1000.00"));
    invoice.setTaxTotal(new BigDecimal("50.00"));
    invoice.setInvoiceTotal(new BigDecimal("1050.00"));
    invoice.setLines(List.of());
    when(purchaseTaxRecorder.pricingByInventoryId(any())).thenReturn(Map.of());
    when(vendorRepository.findById("v1")).thenReturn(Optional.empty());
  }

  private static JournalEntry entry(String id, JournalStatus status) {
    JournalEntry e = new JournalEntry();
    e.setId(id);
    e.setStatus(status);
    return e;
  }

  @Test
  void reversesTheLiveEntryAndPostsTheCorrectedOneUnderANewKey() {
    when(accountingFacade.findBySource("s1", JournalSource.VENDOR_PURCHASE_INVOICE, "inv-1"))
        .thenReturn(Optional.of(entry("je-1", JournalStatus.POSTED)));

    inventoryService.repostAccountingAfterAmend(invoice, "s1", "u1", "subtotal was gross");

    verify(accountingFacade).reverse(eq("s1"), eq("u1"), eq("je-1"), anyString());
    ArgumentCaptor<VendorPurchaseInvoicePostingRequest> posted =
        ArgumentCaptor.forClass(VendorPurchaseInvoicePostingRequest.class);
    verify(accountingFacade).postVendorPurchaseInvoice(eq("s1"), eq("u1"), posted.capture());
    assertTrue(posted.getValue().getSourceId().startsWith("inv-1:amend:"));
    assertEquals(invoice.getLedgerSourceId(), posted.getValue().getSourceId());
    assertEquals(0, new BigDecimal("1050.00").compareTo(posted.getValue().getInvoiceTotal()));
  }

  @Test
  void aSecondAmendmentReversesTheFirstAmendmentsEntry() {
    invoice.setLedgerSourceId("inv-1:amend:1");
    when(accountingFacade.findBySource("s1", JournalSource.VENDOR_PURCHASE_INVOICE, "inv-1:amend:1"))
        .thenReturn(Optional.of(entry("je-2", JournalStatus.POSTED)));

    inventoryService.repostAccountingAfterAmend(invoice, "s1", "u1", "tax corrected");

    verify(accountingFacade).reverse(eq("s1"), eq("u1"), eq("je-2"), anyString());
  }

  @Test
  void anInvoiceNeverPostedIsLeftAlone() {
    when(accountingFacade.findBySource(any(), any(), any())).thenReturn(Optional.empty());

    inventoryService.repostAccountingAfterAmend(invoice, "s1", "u1", "x");

    verify(accountingFacade, never()).reverse(any(), any(), any(), any());
    verify(accountingFacade, never()).postVendorPurchaseInvoice(any(), any(), any());
    assertNull(invoice.getLedgerSourceId());
  }

  @Test
  void anInvoiceWithNoVendorIsLeftAlone() {
    invoice.setVendorId(null);

    inventoryService.repostAccountingAfterAmend(invoice, "s1", "u1", "x");

    verify(accountingFacade, never()).findBySource(any(), any(), any());
  }
}
