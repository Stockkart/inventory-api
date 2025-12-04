package com.inventory.product.rest.dto.inventory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class CreateInventoryRequest {
  private String barcode;
  private String name;
  private String description;
  private String companyName;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private String businessType;
  private String location;
  private Integer count;
  private Instant expiryDate;
  private Instant reminderAt;
  private Instant newReminderAt;
  private Instant reminderEndDate;
  private String reminderNotes;
}

