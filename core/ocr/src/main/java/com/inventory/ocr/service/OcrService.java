package com.inventory.ocr.service;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.prompt.InvoicePricingLayout;
import com.inventory.ocr.provider.OcrProvider;
import com.inventory.ocr.utils.constants.OcrMetricsConstants;
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
  private final MetricsWrapper metrics;

  public OcrService(OcrProvider ocrProvider, MetricsWrapper metrics) {
    this.ocrProvider = ocrProvider;
    this.metrics = metrics;
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
    String provider = ocrProvider.getProviderName();
    log.info("Parsing invoice using provider: {} layout={} ({} bytes)",
        provider, resolved, imageBytes.length);
    try {
      List<ParsedInventoryItem> items =
          metrics.recordLatency(
              OcrMetricsConstants.PARSE,
              () -> {
                try {
                  return ocrProvider.parseInvoice(imageBytes, resolved);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              },
              "module",
              OcrMetricsConstants.MODULE,
              "provider",
              provider);
      int count = items != null ? items.size() : 0;
      log.info("Parsed {} items", count);
      metrics.record(
          OcrMetricsConstants.PARSE_TOTAL,
          1,
          "module",
          OcrMetricsConstants.MODULE,
          "provider",
          provider,
          "outcome",
          "success");
      metrics.record(
          OcrMetricsConstants.ITEMS,
          count,
          "module",
          OcrMetricsConstants.MODULE,
          "provider",
          provider);
      return items != null ? items : List.of();
    } catch (RuntimeException e) {
      metrics.record(
          OcrMetricsConstants.PARSE_TOTAL,
          1,
          "module",
          OcrMetricsConstants.MODULE,
          "provider",
          provider,
          "outcome",
          "error");
      if (e.getCause() instanceof IOException io) {
        throw io;
      }
      throw e;
    }
  }
}
