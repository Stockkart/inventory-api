package com.inventory.ocr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing an inventory item parsed from an invoice image.
 * All date fields are ISO-8601 UTC strings (e.g. 2027-10-01T00:00:00Z) when present.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedInventoryItem {
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String businessType;
  private String location;
  private Integer count;
  private Integer thresholdCount;
  private String expiryDate;       // ISO-8601 UTC, e.g. 2027-10-01T00:00:00Z
  private String reminderAt;       // ISO-8601 UTC
  private List<ParsedReminderDto> customReminders;
  private String hsn;
  private String batchNo;
  private Integer scheme;
  private String sgst;
  private String cgst;

  /**
   * Ensure customReminders is never null.
   */
  public List<ParsedReminderDto> getCustomReminders() {
    return customReminders != null ? customReminders : new ArrayList<>();
  }

  public void setCustomReminders(List<ParsedReminderDto> customReminders) {
    this.customReminders = customReminders != null ? customReminders : new ArrayList<>();
  }
}
