package com.inventory.reminders.api;

import java.math.BigDecimal;

/** What a reminder shows about the stock lot it belongs to. */
public record LotSummary(
    String id,
    String lotId,
    String name,
    String companyName,
    String location,
    String vendorId,
    String batchNo,
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal priceToRetail) {}
