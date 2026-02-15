package com.inventory.product.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "purchases")
public class Purchase {

  @Id
  private String id;
  private String invoiceNo;
  private String businessType;
  private String userId;
  private String shopId;
  private List<PurchaseItem> items;
  private BigDecimal subTotal;
  private BigDecimal taxTotal;
  private BigDecimal sgstAmount; // Calculated SGST amount
  private BigDecimal cgstAmount; // Calculated CGST amount
  private BigDecimal discountTotal;
  private BigDecimal additionalDiscountTotal;
  private BigDecimal grandTotal;
  /** Margin breakdown: total cost (inventory cost price × quantities). */
  private BigDecimal totalCost;
  /** Revenue before tax (taxable value): subTotal − additionalDiscountTotal. */
  private BigDecimal revenueBeforeTax;
  /** Revenue after tax (total amount received): grandTotal. */
  private BigDecimal revenueAfterTax;
  /** Total profit: revenueBeforeTax − totalCost. */
  private BigDecimal totalProfit;
  /** Overall margin percentage: (totalProfit / revenueBeforeTax) × 100. */
  private BigDecimal marginPercent;
  private Instant soldAt;
  private boolean valid;
  private String paymentMethod;
  private PurchaseStatus status;
  private String customerId;
  private String customerName; // Used when only name is provided without phone
  private Instant createdAt;
  private Instant updatedAt;
}

