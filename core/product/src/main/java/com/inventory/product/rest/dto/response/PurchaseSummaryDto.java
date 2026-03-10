package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.BillingMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseSummaryDto {
  private String purchaseId;
  private String invoiceNo;
  private String businessType;
  private BillingMode billingMode;
  private String userId;
  private String shopId;
  private List<PurchaseItem> items;
  private BigDecimal subTotal;
  private BigDecimal taxTotal;
  private BigDecimal sgstAmount; // Calculated SGST amount
  private BigDecimal cgstAmount; // Calculated CGST amount
  private BigDecimal discountTotal;
  private BigDecimal additionalDiscountTotal; // Total additional discount amount
  private BigDecimal grandTotal;
  private BigDecimal totalCost;       // Margin: total cost (inventory cost × quantities)
  private BigDecimal revenueBeforeTax; // Margin: subTotal − additionalDiscountTotal
  private BigDecimal revenueAfterTax;  // Margin: grandTotal (total received including tax)
  private BigDecimal totalProfit;     // Margin: revenueBeforeTax − totalCost
  private BigDecimal marginPercent;   // Margin: (totalProfit / revenueBeforeTax) × 100
  private Instant soldAt;
  private PurchaseStatus status;
  private String paymentMethod;
  private String customerId;
  private String customerName;
  private String customerAddress;
  private String customerPhone;
}

