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

  /** Price to Retail (PTR). Immutable after creation. Base price when defaultRate is "priceToRetail". */
  private BigDecimal priceToRetail;

  /** Named rates (e.g., Rate-A=100, Rate-B=80). Optional. */
  private List<Rate> rates;

  /**
   * Default price source. One of: "maximumRetailPrice", "priceToRetail", "costPrice", or a rate name from rates.
   * When not provided at creation, defaults to "priceToRetail".
   */
  private String defaultRate;

  /** Selling price (effective price for sales). Updated when defaultRate changes. Can be set directly. */
  private BigDecimal sellingPrice;

  /** Additional discount percentage (0-100) */
  private BigDecimal additionalDiscount;

  /** State GST rate (e.g., "9" for 9%) */
  private String sgst;

  /** Central GST rate (e.g., "9" for 9%) */
  private String cgst;

  private Instant createdAt;
  private Instant updatedAt;
}
