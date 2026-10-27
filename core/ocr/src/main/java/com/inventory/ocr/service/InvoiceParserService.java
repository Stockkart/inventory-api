package com.inventory.ocr.service;

import com.inventory.ocr.api.InvoiceParser;
import com.inventory.ocr.api.InvoicePricingLayout;
import com.inventory.ocr.api.ParsedInvoiceItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Entry point for invoice parsing. Delegates to {@link OcrService} (provider-based).
 */
@Service
@Slf4j
public class InvoiceParserService implements InvoiceParser {

  @Autowired
  private OcrService ocrService;

  public List<ParsedInvoiceItem> parseInvoiceImage(byte[] imageBytes) {
    return parseInvoiceImage(imageBytes, InvoicePricingLayout.WHOLESALER);
  }

  /**
   * Parse an invoice image and extract inventory line items.
   *
   * @param imageBytes the image file as byte array
   * @param layout which price columns the model should read
   * @return list of parsed inventory items
   */
  @Override
  public List<ParsedInvoiceItem> parseInvoiceImage(byte[] imageBytes, InvoicePricingLayout layout) {
    InvoicePricingLayout resolved = InvoicePricingLayout.orDefault(layout);
    log.info("Parsing invoice image ({} bytes) layout={}", imageBytes.length, resolved);
    try {
      return ParsedItems.toApi(ocrService.parseInvoice(imageBytes, resolved));
    } catch (IOException e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to parse invoice image: " + e.getMessage(), e);
    } catch (Exception e) {
      log.error("Error parsing invoice image: {}", e.getMessage(), e);
      throw new RuntimeException("Failed to parse invoice image: " + e.getMessage(), e);
    }
  }
}
