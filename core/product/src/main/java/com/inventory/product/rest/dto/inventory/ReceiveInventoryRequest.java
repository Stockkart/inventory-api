package com.inventory.product.rest.dto.inventory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class ReceiveInventoryRequest {
  private String barcode;
  private Integer count;
  private Instant expiryDate;
  private String location;
  private String shopId;
  private String userId;
  private String name;
  private BigDecimal price;
  private Instant reminderAt;
  private Instant newReminderAt;
  private Instant reminderEndDate;
  private String reminderNotes;
}

