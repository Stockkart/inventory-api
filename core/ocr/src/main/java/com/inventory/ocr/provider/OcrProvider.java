package com.inventory.ocr.provider;

import com.inventory.ocr.dto.ParsedInventoryItem;

import java.io.IOException;
import java.util.List;

/**
 * Interface for invoice parsing providers. Each provider returns parsed line items directly.
 */
public interface OcrProvider {

  /**
   * Parse an invoice image and extract inventory line items.
   *
   * @param imageBytes the image file as byte array
   * @return list of parsed inventory items (never null)
   * @throws IOException if image cannot be read or processing fails
   */
  List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException;

  /**
   * Provider identifier (e.g. "AWS_TEXTTRACT", "CHATGPT_4O_MINI").
   */
  String getProviderName();
}
