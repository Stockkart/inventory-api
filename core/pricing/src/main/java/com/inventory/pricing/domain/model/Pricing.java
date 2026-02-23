package com.inventory.pricing.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Pricing data for an inventory item.
 * One-to-one with inventory: each inventory has exactly one pricing record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "pricing")
public class Pricing {

  @Id
  private String id;

  private String shopId;

  /** Maximum Retail Price (MRP) */
  private BigDecimal maximumRetailPrice;

  /** Cost Price (CP) */
  private BigDecimal costPrice;

  /** Selling Price (SP). Used when defaultRate is not set or rates is empty. */
  private BigDecimal sellingPrice;

  /** Named rates (e.g., Rate-A=100, Rate-B=80). Optional. */
  private List<Rate> rates;

  /** Name of the default rate from rates. When set, effective price = that rate's price; else sellingPrice. */
  private String defaultRate;

  /** Additional discount percentage (0-100) */
  private BigDecimal additionalDiscount;

  /** State GST rate (e.g., "9" for 9%) */
  private String sgst;

  /** Central GST rate (e.g., "9" for 9%) */
  private String cgst;

  private Instant createdAt;
  private Instant updatedAt;
}
