package com.inventory.ocr.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
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

  /**
   * Parse an invoice image and extract inventory line items.
   *
   * @param imageBytes the image file as byte array
   * @return list of parsed inventory items (never null)
   * @throws IOException if image cannot be read or processing fails
   */
  public List<ParsedInventoryItem> parseInvoice(byte[] imageBytes) throws IOException {
    log.info("Parsing invoice using provider: {} ({} bytes)", ocrProvider.getProviderName(), imageBytes.length);
    List<ParsedInventoryItem> items = ocrProvider.parseInvoice(imageBytes);
    log.info("Parsed {} items", items != null ? items.size() : 0);
    return items != null ? items : List.of();
  }
}
