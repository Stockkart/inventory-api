package com.inventory.ocr.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.prompt.InvoicePricingLayout;
import com.inventory.ocr.provider.OcrProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Delegates invoice parsing to the configured {@link OcrProvider}.
 */
@Service
@Slf4j
public class OcrService {

  private final OcrProvider ocrProvider;

  public OcrService(OcrProvider ocrProvider) {
    this.ocrProvider = ocrProvider;
    log.info("OcrService initialized with provider: {}", ocrProvider.getProviderName());
  }

  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    return parseInvoice(imageBytes, InvoicePricingLayout.WHOLESALER);
  }

  /**
   * Parse an invoice image and extract inventory line items.
   *
   * @param imageBytes the image file as byte array
   * @param layout which price columns the model should read
   * @return list of parsed inventory items (never null)
   * @throws IOException if image cannot be read or processing fails
   */
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes, InvoicePricingLayout layout)
      throws IOException {
    InvoicePricingLayout resolved = InvoicePricingLayout.orDefault(layout);
    log.info("Parsing invoice using provider: {} layout={} ({} bytes)",
        ocrProvider.getProviderName(), resolved, imageBytes.length);
    List<ParsedInventoryItem> items = ocrProvider.parseInvoice(imageBytes, resolved);
    log.info("Parsed {} items", items != null ? items.size() : 0);
    return items != null ? items : List.of();
  }
}
