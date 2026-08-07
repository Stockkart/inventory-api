package com.inventory.product.service.creditnote;

import com.inventory.documentservice.rest.dto.GenerateCreditNoteRequest;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopInvoiceSettingsDocument;

/**
 * Strategy that loads a domain credit-note source and maps it into a
 * {@link GenerateCreditNoteRequest}. Implementations are registered as Spring beans
 * and selected by {@link CreditNotePartyRole}.
 */
public interface CreditNoteDocumentAssembler {

  CreditNotePartyRole partyRole();

  /**
   * Build a print request for the given document id within the shop.
   *
   * @param documentId refund id or vendor purchase return id
   * @param shopId active shop
   * @param shop shop entity (already loaded)
   * @param settings shop invoice settings (visibility + footer)
   */
  GenerateCreditNoteRequest assemble(
      String documentId, String shopId, Shop shop, ShopInvoiceSettingsDocument settings);
}
