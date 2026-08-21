package com.inventory.product.rest.dto.response;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.model.enums.BillingMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddToCartResponse {
  String purchaseId;
  String invoiceNo;
  String businessType;
  BillingMode billingMode;
  String userId;
  String shopId;
  List<PurchaseItem> items;
  BigDecimal subTotal;
  BigDecimal taxTotal;
  BigDecimal sgstAmount; // Calculated SGST amount
  BigDecimal cgstAmount; // Calculated CGST amount
  BigDecimal discountTotal;
  BigDecimal saleAdditionalDiscountTotal; // Total additional discount amount
  BigDecimal grandTotal;
  BigDecimal totalCost;       // Margin: total cost (inventory cost × quantities)
  BigDecimal revenueBeforeTax; // Margin: subTotal − additionalDiscountTotal
  BigDecimal revenueAfterTax;  // Margin: grandTotal (total received including tax)
  BigDecimal totalProfit;     // Margin: revenueBeforeTax − totalCost
  BigDecimal marginPercent;   // Markup on cost: (totalProfit / totalCost) × 100
  PurchaseStatus status;
  String customerId;
  String customerName;
  String customerAddress;
  String customerPhone;
  String tokenNo;
  String customerGstin;
  String customerDlNo;
  String customerPan;
  String paymentMethod;
  /** SALE (default) or ESTIMATE. */
  com.inventory.product.domain.model.enums.DocumentType documentType;
  /** Present when documentType is ESTIMATE. */
  com.inventory.product.domain.model.enums.EstimateState estimateState;
  String estimateNo;
  String convertedToPurchaseId;
  String sourceEstimateId;
}

