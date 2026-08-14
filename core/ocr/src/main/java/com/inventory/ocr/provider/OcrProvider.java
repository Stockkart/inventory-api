package com.inventory.ocr.provider;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.prompt.InvoicePricingLayout;

import java.io.IOException;
import java.util.List;

/**
 * Interface for invoice parsing providers. Each provider returns parsed line items directly.
 */
public interface OcrProvider {

  /**
   * Parse an invoice image using the wholesaler (PTS/PTR/MRP) layout.
   */
  default List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    return parseInvoice(imageBytes, InvoicePricingLayout.WHOLESALER);
  }

  /**
   * Parse an invoice image and extract inventory line items.
   *
   * @param imageBytes the image file as byte array
   * @param layout which price columns the model should read; ignored by table-based providers
   * @return list of parsed inventory items (never null)
   * @throws IOException if image cannot be read or processing fails
   */
  List<ParsedInventoryItem> parseInvoice(byte[] imageBytes, InvoicePricingLayout layout) throws IOException;

  /**
   * Provider identifier (e.g. "AWS_TEXTTRACT", "CHATGPT_4O_MINI").
   */
  String getProviderName();
}
