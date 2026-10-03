package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.rest.dto.request.VendorPurchaseInvoiceRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The totals stock-in was sent are the ones stored; worked-out figures fill only the gaps. */
class InventoryServiceKeepSentTotalsTest {

  private static VendorPurchaseInvoice workedOut() {
    VendorPurchaseInvoice invoice = new VendorPurchaseInvoice();
    invoice.setLineSubTotal(new BigDecimal("2579.66"));
    invoice.setTaxTotal(new BigDecimal("128.98"));
    invoice.setInvoiceTotal(new BigDecimal("2709.00"));
    return invoice;
  }

  @Test
  void sentTotalsAreStoredAsSent() {
    VendorPurchaseInvoiceRequest sent = new VendorPurchaseInvoiceRequest();
    sent.setLineSubTotal(new BigDecimal("2580.00"));
    sent.setTaxTotal(new BigDecimal("129.00"));
    sent.setInvoiceTotal(new BigDecimal("2709.00"));
    VendorPurchaseInvoice invoice = workedOut();

    InventoryService.keepSentTotals(invoice, sent);

    assertEquals(new BigDecimal("2580.00"), invoice.getLineSubTotal());
    assertEquals(new BigDecimal("129.00"), invoice.getTaxTotal());
    assertEquals(new BigDecimal("2709.00"), invoice.getInvoiceTotal());
  }

  @Test
  void aTotalLeftOutKeepsTheWorkedOutFigure() {
    VendorPurchaseInvoiceRequest sent = new VendorPurchaseInvoiceRequest();
    sent.setTaxTotal(new BigDecimal("129.00"));
    VendorPurchaseInvoice invoice = workedOut();

    InventoryService.keepSentTotals(invoice, sent);

    assertEquals(new BigDecimal("2579.66"), invoice.getLineSubTotal());
    assertEquals(new BigDecimal("129.00"), invoice.getTaxTotal());
    assertEquals(new BigDecimal("2709.00"), invoice.getInvoiceTotal());

    VendorPurchaseInvoice untouched = workedOut();
    InventoryService.keepSentTotals(untouched, null);
    assertEquals(new BigDecimal("128.98"), untouched.getTaxTotal());
  }
}
