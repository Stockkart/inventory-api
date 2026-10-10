package com.inventory.documentservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.documentservice.domain.DotMatrixDocumentKind;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.documentservice.rest.dto.GenerateInvoiceRequest;
import org.junit.jupiter.api.Test;

/** The print bridge is told what the paper says: the same rule decides both. */
class InvoiceTextRendererKindTest {

  @Test
  void aBasicSaleOrAnEstimateIsAnEstimate() {
    GenerateInvoiceRequest basic = new GenerateInvoiceRequest();
    basic.setBillingMode("BASIC");
    assertEquals(DotMatrixDocumentKind.ESTIMATE, InvoiceTextRenderer.kindOf(basic));

    GenerateInvoiceRequest estimate = new GenerateInvoiceRequest();
    estimate.setDocumentType("ESTIMATE");
    assertEquals(DotMatrixDocumentKind.ESTIMATE, InvoiceTextRenderer.kindOf(estimate));
  }

  @Test
  void aRegularSaleIsAnInvoice() {
    GenerateInvoiceRequest regular = new GenerateInvoiceRequest();
    regular.setBillingMode("REGULAR");
    assertEquals(DotMatrixDocumentKind.INVOICE, InvoiceTextRenderer.kindOf(regular));
  }

  @Test
  void aVendorNoteIsADebitNoteAndAnythingElseACreditNote() {
    GenerateCreditNoteRequest vendor = new GenerateCreditNoteRequest();
    vendor.setPartyRole("VENDOR");
    assertEquals(DotMatrixDocumentKind.DEBIT_NOTE, InvoiceTextRenderer.kindOf(vendor));

    assertEquals(
        DotMatrixDocumentKind.CREDIT_NOTE,
        InvoiceTextRenderer.kindOf(new GenerateCreditNoteRequest()));
  }
}
