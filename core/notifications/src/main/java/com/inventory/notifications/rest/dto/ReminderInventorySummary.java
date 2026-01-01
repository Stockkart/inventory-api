package com.inventory.notifications.rest.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ReminderInventorySummary {
  private String id;
  private String lotId;
  private String name;
  private String companyName;
  private String location;
  private String vendorId;
  private String batchNo;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
}