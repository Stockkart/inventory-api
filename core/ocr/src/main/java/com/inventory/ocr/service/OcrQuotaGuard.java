package com.inventory.ocr.service;

/**
 * Meters invoice scans against a shop's OCR quota. Implemented outside this module; when absent,
 * scans are not metered.
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
