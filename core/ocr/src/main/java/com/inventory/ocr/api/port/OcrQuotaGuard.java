package com.inventory.ocr.api.port;

/**
 * Meters invoice scans against a shop's OCR quota. Implemented by the product module
 * ({@code product/service/api/OcrQuotaGuardImpl}), which delegates to the plan api.
 *
 * <p>Callers check before calling the provider and record only after a successful parse, so failed
 * calls and retries of failed calls cost nothing.
 */
public interface OcrQuotaGuard {

  /** Throws when the shop has no OCR units left. */
  void requireUnit(String shopId);

  /** Consumes one unit: one successfully processed invoice, however many pages. */
  void recordUnit(String shopId);
}
