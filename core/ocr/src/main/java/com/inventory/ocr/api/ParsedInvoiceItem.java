package com.inventory.ocr.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * One inventory line read from a supplier invoice (OCR) or a stock sheet (Excel), before the
 * caller turns it into a stock-in request. Fields the source did not contain are null.
 *
 * @param customReminders never null; empty when the source had none
 */
public record ParsedInvoiceItem(
    String barcode,
    String name,
    String description,
    String companyName,
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal priceToRetail,
    BigDecimal saleAdditionalDiscount,
    String businessType,
    String location,
    Integer count,
    Integer thresholdCount,
    String expiryDate,  // ISO-8601 UTC, e.g. 2027-10-01T00:00:00Z
    String reminderAt,  // ISO-8601 UTC
    List<ParsedReminder> customReminders,
    String hsn,
    String batchNo,
    Integer scheme,
    Integer schemePayFor,
    Integer schemeFree,
    Integer purchaseSchemePayFor,
    Integer purchaseSchemeFree,
    String purchaseDate,
    String packDetail,
    String baseUnit,
    Integer unitsPerPack,
    String sgst,
    String cgst) {

  public ParsedInvoiceItem {
    customReminders = customReminders == null ? List.of() : customReminders;
  }
}
