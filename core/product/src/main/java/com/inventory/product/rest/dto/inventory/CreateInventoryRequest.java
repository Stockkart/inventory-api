package com.inventory.product.rest.dto.inventory;

import com.inventory.common.dto.CustomReminderRequest;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
  private List<CustomReminderRequest> customReminders;
}

