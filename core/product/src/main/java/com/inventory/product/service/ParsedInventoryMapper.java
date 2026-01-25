package com.inventory.product.service;

import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.dto.ParsedReminderDto;
import com.inventory.notifications.rest.dto.CustomReminderRequest;
import com.inventory.product.rest.dto.inventory.CreateInventoryItemRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mapper to convert ParsedInventoryItem to CreateInventoryItemRequest.
 */
@Component
@Slf4j
public class ParsedInventoryMapper {

  /**
   * Convert a ParsedInventoryItem to CreateInventoryItemRequest.
   *
   * @param parsedItem the parsed inventory item from OCR
   * @return CreateInventoryItemRequest for bulk creation
   */
  public CreateInventoryItemRequest toCreateInventoryItemRequest(ParsedInventoryItem parsedItem) {
    CreateInventoryItemRequest request = new CreateInventoryItemRequest();

    request.setBarcode(parsedItem.getBarcode());
    request.setName(parsedItem.getName());
    request.setDescription(parsedItem.getDescription());
    request.setCompanyName(parsedItem.getCompanyName());
    request.setMaximumRetailPrice(parsedItem.getMaximumRetailPrice());
    request.setCostPrice(parsedItem.getCostPrice());
    request.setSellingPrice(parsedItem.getSellingPrice());
    request.setAdditionalDiscount(parsedItem.getAdditionalDiscount());
    request.setBusinessType(parsedItem.getBusinessType() != null ? parsedItem.getBusinessType() : "PHARMACEUTICAL");
    request.setLocation(parsedItem.getLocation());
    request.setCount(parsedItem.getCount());
    request.setThresholdCount(parsedItem.getThresholdCount() != null ? parsedItem.getThresholdCount() : 10);
    request.setHsn(parsedItem.getHsn());
    request.setBatchNo(parsedItem.getBatchNo());
    request.setScheme(parsedItem.getScheme());
    request.setSgst(parsedItem.getSgst());
    request.setCgst(parsedItem.getCgst());

    if (parsedItem.getExpiryDate() != null && !parsedItem.getExpiryDate().trim().isEmpty()) {
      try {
        Instant expiry = Instant.parse(parsedItem.getExpiryDate());
        request.setExpiryDate(expiry);
      } catch (Exception e) {
        log.warn("Could not parse expiryDate '{}': {}", parsedItem.getExpiryDate(), e.getMessage());
      }
    }

    if (parsedItem.getReminderAt() != null && !parsedItem.getReminderAt().trim().isEmpty()) {
      try {
        request.setReminderAt(Instant.parse(parsedItem.getReminderAt()));
      } catch (Exception e) {
        log.warn("Could not parse reminderAt '{}': {}", parsedItem.getReminderAt(), e.getMessage());
      }
    }

    List<ParsedReminderDto> parsedReminders = parsedItem.getCustomReminders();
    if (parsedReminders != null && !parsedReminders.isEmpty()) {
      List<CustomReminderRequest> reminders = new ArrayList<>();
      for (ParsedReminderDto r : parsedReminders) {
        try {
          Instant at = r.getReminderAt() != null && !r.getReminderAt().isEmpty() ? Instant.parse(r.getReminderAt()) : null;
          Instant end = r.getEndDate() != null && !r.getEndDate().isEmpty() ? Instant.parse(r.getEndDate()) : null;
          if (at != null || end != null) {
            CustomReminderRequest cr = new CustomReminderRequest();
            cr.setReminderAt(at);
            cr.setEndDate(end);
            cr.setNotes(r.getNotes());
            reminders.add(cr);
          }
        } catch (Exception e) {
          log.warn("Could not parse reminder: {}", e.getMessage());
        }
      }
      request.setCustomReminders(reminders);
    }

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
        }
      }
    }
    return requests;
  }
}
