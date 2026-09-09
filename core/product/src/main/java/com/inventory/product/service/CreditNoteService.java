package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.documentservice.domain.PrinterType;
import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.service.creditnote.CreditNoteDocumentAssembler;
import com.inventory.product.service.creditnote.CreditNotePartyRole;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates credit-note PDF generation. Assemblers are selected by
 * {@link CreditNotePartyRole} (Strategy) so new note sources can be added
 * without changing this facade.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class CreditNoteService {

  private final ShopRepository shopRepository;
  private final InvoiceSettingsService invoiceSettingsService;
  private final DocumentService documentService;
  private final Map<CreditNotePartyRole, CreditNoteDocumentAssembler> assemblersByRole;
  private final MetricsWrapper metrics;

  public CreditNoteService(
      ShopRepository shopRepository,
      InvoiceSettingsService invoiceSettingsService,
      DocumentService documentService,
      List<CreditNoteDocumentAssembler> assemblers,
      MetricsWrapper metrics) {
    this.shopRepository = shopRepository;
    this.invoiceSettingsService = invoiceSettingsService;
    this.documentService = documentService;
    this.metrics = metrics;
    this.assemblersByRole = new EnumMap<>(CreditNotePartyRole.class);
    if (assemblers != null) {
      for (CreditNoteDocumentAssembler assembler : assemblers) {
        if (assembler != null && assembler.partyRole() != null) {
          this.assemblersByRole.put(assembler.partyRole(), assembler);
        }
      }
    }
  }

  public byte[] generateCustomerCreditNotePdf(String refundId, String shopId, String printerType) {
    return generatePdf(CreditNotePartyRole.CUSTOMER, refundId, shopId, printerType);
  }

  public byte[] generateVendorCreditNotePdf(String returnId, String shopId, String printerType) {
    return generatePdf(CreditNotePartyRole.VENDOR, returnId, shopId, printerType);
  }

  /** The customer's credit note as plain text for the dot matrix bridge. */
  public String generateCustomerCreditNoteText(String refundId, String shopId) {
    return generateText(CreditNotePartyRole.CUSTOMER, refundId, shopId);
  }

  /** The vendor's debit note as plain text for the dot matrix bridge. */
  public String generateVendorCreditNoteText(String returnId, String shopId) {
    return generateText(CreditNotePartyRole.VENDOR, returnId, shopId);
  }

  /**
   * Assembles the note and renders it as text.
   *
   * <p>The printer type is forced to DOT_MATRIX rather than taken from the shop's settings or a
   * query parameter: this path exists only to feed the print bridge, and text is the one thing
   * it can send. Honouring a setting that said NORMAL here would return a document laid out for
   * a laser printer down a pipe that can only print characters.
   */
  private String generateText(CreditNotePartyRole role, String documentId, String shopId) {
    log.info("Generating {} note text for document={}, shop={}", role, documentId, shopId);
    var request = assemble(role, documentId, shopId);
    request.setPrinterType(PrinterType.DOT_MATRIX.name());
    return documentService.generateCreditNoteText(request);
  }

  private byte[] generatePdf(
      CreditNotePartyRole role, String documentId, String shopId, String printerType) {
    log.info(
        "Generating {} credit note PDF for document={}, shop={}", role, documentId, shopId);

    var request = assemble(role, documentId, shopId);
    ShopInvoiceSettingsDocument settings = invoiceSettingsService.getOrDefaultForShop(shopId);
    String resolvedPrinter =
        StringUtils.hasText(printerType) ? printerType : settings.getDefaultPrinterType();
    request.setPrinterType(resolvedPrinter);

    byte[] pdf = documentService.generateCreditNote(request);
    metrics.record(
        ProductMetricsConstants.CREDIT_NOTES_TOTAL,
        1,
        "module",
        ProductMetricsConstants.MODULE);
    return pdf;
  }

  /** Loads the shop and its settings and builds the note, whichever way it is to be rendered. */
  private GenerateCreditNoteRequest assemble(
      CreditNotePartyRole role, String documentId, String shopId) {
    CreditNoteDocumentAssembler assembler = assemblersByRole.get(role);
    if (assembler == null) {
      throw new IllegalStateException("No credit-note assembler registered for " + role);
    }
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    ShopInvoiceSettingsDocument settings = invoiceSettingsService.getOrDefaultForShop(shopId);
    return assembler.assemble(documentId, shopId, shop, settings);
  }
}
