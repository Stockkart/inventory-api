package com.inventory.ocr.api;

/** A reminder read with a {@link ParsedInvoiceItem}; dates are ISO-8601 UTC strings. */
public record ParsedReminder(String reminderAt, String endDate, String notes) {}
