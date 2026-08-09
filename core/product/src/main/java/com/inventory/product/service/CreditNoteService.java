package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.documentservice.service.DocumentService;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.service.creditnote.CreditNoteDocumentAssembler;
import com.inventory.product.service.creditnote.CreditNotePartyRole;
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

  public CreditNoteService(
      ShopRepository shopRepository,
      InvoiceSettingsService invoiceSettingsService,
      DocumentService documentService,
      List<CreditNoteDocumentAssembler> assemblers) {
    this.shopRepository = shopRepository;
    this.invoiceSettingsService = invoiceSettingsService;
    this.documentService = documentService;
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

  private byte[] generatePdf(
      CreditNotePartyRole role, String documentId, String shopId, String printerType) {
    log.info(
        "Generating {} credit note PDF for document={}, shop={}", role, documentId, shopId);

    CreditNoteDocumentAssembler assembler = assemblersByRole.get(role);
    if (assembler == null) {
      throw new IllegalStateException("No credit-note assembler registered for " + role);
    }

    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    ShopInvoiceSettingsDocument settings = invoiceSettingsService.getOrDefaultForShop(shopId);

    var request = assembler.assemble(documentId, shopId, shop, settings);
    String resolvedPrinter =
        StringUtils.hasText(printerType) ? printerType : settings.getDefaultPrinterType();
    request.setPrinterType(resolvedPrinter);

    return documentService.generateCreditNote(request);
  }
}
