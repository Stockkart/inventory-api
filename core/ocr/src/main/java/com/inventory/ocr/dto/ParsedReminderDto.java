package com.inventory.ocr.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Reminder parsed from invoice or derived (e.g. expiry reminder).
 * All date fields are ISO-8601 UTC strings (e.g. 2027-10-01T00:00:00Z).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ParsedReminderDto {
  private String reminderAt;
  private String endDate;
  private String notes;
}
