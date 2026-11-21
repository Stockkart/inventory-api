package com.inventory.ocr.service;

import com.inventory.ocr.api.ParsedInvoiceItem;
import com.inventory.ocr.api.ParsedReminder;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.dto.ParsedReminderDto;
import java.util.List;

/**
 * Converts ocr's working types (mutable, filled field by field while parsing) into the api
 * records other modules receive. Every field is copied; ParsedItemsTest checks none is missed.
 */
public final class ParsedItems {

  private ParsedItems() {}

  public static List<ParsedInvoiceItem> toApi(List<ParsedInventoryItem> items) {
    // Null entries pass through as null, exactly as the caller's mapper received them before.
    return items == null ? null : items.stream().map(i -> i == null ? null : toApi(i)).toList();
  }

  public static ParsedInvoiceItem toApi(ParsedInventoryItem i) {
    List<ParsedReminder> reminders = i.getCustomReminders().stream()
        .map(r -> r == null ? null : toApi(r))
        .toList();
    return new ParsedInvoiceItem(
        i.getBarcode(),
        i.getName(),
        i.getDescription(),
        i.getCompanyName(),
        i.getMaximumRetailPrice(),
        i.getCostPrice(),
        i.getPriceToRetail(),
        i.getSaleAdditionalDiscount(),
        i.getBusinessType(),
        i.getLocation(),
        i.getCount(),
        i.getThresholdCount(),
        i.getExpiryDate(),
        i.getReminderAt(),
        reminders,
        i.getHsn(),
        i.getBatchNo(),
        i.getScheme(),
        i.getSchemePayFor(),
        i.getSchemeFree(),
        i.getPurchaseSchemePayFor(),
        i.getPurchaseSchemeFree(),
        i.getPurchaseDate(),
        i.getPackDetail(),
        i.getBaseUnit(),
        i.getUnitsPerPack(),
        i.getSgst(),
        i.getCgst());
  }

  static ParsedReminder toApi(ParsedReminderDto r) {
    return new ParsedReminder(r.getReminderAt(), r.getEndDate(), r.getNotes());
  }
}
