package com.inventory.product.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.product.rest.dto.inventory.CreateInventoryItemRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.inventory.notifications.rest.dto.CustomReminderRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mapper to convert ParsedInventoryItem to CreateInventoryItemRequest.
 */
@Component
@Slf4j
public class ParsedInventoryMapper {

  private static final Pattern DATE_PATTERN = Pattern.compile("([A-Z]{3})[-.](\\d{2,4})");

  /**
   * Convert a ParsedInventoryItem to CreateInventoryItemRequest.
   *
   * @param parsedItem the parsed inventory item from OCR
   * @return CreateInventoryItemRequest for bulk creation
   */
  public CreateInventoryItemRequest toCreateInventoryItemRequest(ParsedInventoryItem parsedItem) {
    CreateInventoryItemRequest request = new CreateInventoryItemRequest();

    // Direct mappings
    request.setBarcode(parsedItem.getCode());
    request.setName(parsedItem.getName());
    request.setDescription(parsedItem.getName()); // Use name as description
    request.setHsn(parsedItem.getHsn());
    request.setBatchNo(parsedItem.getBatchNo());
    request.setCount(parsedItem.getQuantity());
    
    // Price mappings
    request.setMaximumRetailPrice(parsedItem.getMrp());
    request.setSellingPrice(parsedItem.getRate()); // Rate from invoice goes to sellingPrice
    // costPrice is not set from invoice parsing (can be set manually if needed)

    // Parse expiry date
    if (parsedItem.getExpiryDate() != null && !parsedItem.getExpiryDate().trim().isEmpty()) {
      Instant expiryInstant = parseDateString(parsedItem.getExpiryDate());
      request.setExpiryDate(expiryInstant);
      
      // Set reminder 30 days before expiry
      if (expiryInstant != null) {
        Instant reminderAt = expiryInstant.minusSeconds(30 * 24 * 60 * 60); // 30 days before
        request.setReminderAt(reminderAt);
        
        // Add custom reminder
        List<CustomReminderRequest> reminders = new ArrayList<>();
        CustomReminderRequest reminder = new CustomReminderRequest();
        reminder.setReminderAt(reminderAt);
        reminder.setEndDate(expiryInstant);
        reminder.setNotes("Expiry reminder - 30 days before expiry");
        reminders.add(reminder);
        request.setCustomReminders(reminders);
      }
    }

    // Set default values for fields not available in parsed item
    request.setCompanyName(parsedItem.getCompanyName()); // Not available in invoice
    request.setBusinessType("PHARMACEUTICAL"); // Default, can be overridden
    request.setLocation(null); // Not available in invoice
    request.setThresholdCount(10); // Default threshold
    request.setScheme(null); // Not available in invoice

    return request;
  }

  /**
   * Convert a list of ParsedInventoryItem to CreateInventoryItemRequest.
   *
   * @param parsedItems list of parsed inventory items
   * @return list of CreateInventoryItemRequest
   */
  public List<CreateInventoryItemRequest> toCreateInventoryItemRequestList(
      List<ParsedInventoryItem> parsedItems) {
    List<CreateInventoryItemRequest> requests = new ArrayList<>();
    
    if (parsedItems != null) {
      for (ParsedInventoryItem item : parsedItems) {
        try {
          requests.add(toCreateInventoryItemRequest(item));
        } catch (Exception e) {
          log.error("Error converting ParsedInventoryItem to CreateInventoryItemRequest: {}", 
              e.getMessage(), e);
          // Continue with next item
        }
      }
    }
    
    return requests;
  }

  /**
   * Parse date string in formats like "OCT-27", "NOV-24", "OCT.27", etc.
   * Assumes current or next year if only 2 digits are provided.
   *
   * @param dateStr date string to parse
   * @return Instant representing the date, or null if parsing fails
   */
  private Instant parseDateString(String dateStr) {
    if (dateStr == null || dateStr.trim().isEmpty()) {
      return null;
    }

    try {
      // Remove extra whitespace
      dateStr = dateStr.trim().toUpperCase();

      // Match patterns like "OCT-27", "NOV-24", "OCT.27", "OCT-2027", etc.
      Matcher matcher = DATE_PATTERN.matcher(dateStr);
      if (!matcher.find()) {
        log.warn("Date string '{}' does not match expected pattern", dateStr);
        return null;
      }

      String monthStr = matcher.group(1);
      String dayOrYearStr = matcher.group(2);

      int currentYear = LocalDate.now().getYear();
      int day;
      int year;

      // Determine if it's day or year based on length and value
      if (dayOrYearStr.length() == 2) {
        int value = Integer.parseInt(dayOrYearStr);
        // If value >= 20, assume it's a year (20XX format, e.g., "27" = 2027)
        // Otherwise, treat it as a day
        if (value >= 20) {
          year = 2000 + value; // Convert "27" to 2027
          day = 1; // Use first day of month for expiry dates
        } else {
          // It's a day, use current or next year
          day = value;
          int monthValue = getMonthValue(monthStr);
          if (monthValue == -1) {
            return null;
          }
          LocalDate testDate = LocalDate.of(currentYear, monthValue, day);
          if (testDate.isBefore(LocalDate.now())) {
            year = currentYear + 1;
          } else {
            year = currentYear;
          }
        }
      } else if (dayOrYearStr.length() == 4) {
        // It's a full year, assume day 1 of the month
        year = Integer.parseInt(dayOrYearStr);
        day = 1;
      } else {
        log.warn("Unexpected date format: {}", dateStr);
        return null;
      }

      int monthValue = getMonthValue(monthStr);
      if (monthValue == -1) {
        return null;
      }

      LocalDate date = LocalDate.of(year, monthValue, day);
      return date.atStartOfDay().toInstant(ZoneOffset.UTC);

    } catch (Exception e) {
      log.warn("Error parsing date string '{}': {}", dateStr, e.getMessage());
      return null;
    }
  }

  /**
   * Convert month abbreviation to month value (1-12).
   *
   * @param monthStr month abbreviation (e.g., "JAN", "FEB", "OCT")
   * @return month value (1-12), or -1 if invalid
   */
  private int getMonthValue(String monthStr) {
    switch (monthStr.toUpperCase()) {
      case "JAN": return 1;
      case "FEB": return 2;
      case "MAR": return 3;
      case "APR": return 4;
      case "MAY": return 5;
      case "JUN": return 6;
      case "JUL": return 7;
      case "AUG": return 8;
      case "SEP": return 9;
      case "OCT": return 10;
      case "NOV": return 11;
      case "DEC": return 12;
      default:
        log.warn("Invalid month abbreviation: {}", monthStr);
        return -1;
    }
  }
}

