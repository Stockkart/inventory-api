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
public class CheckoutResponse {
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
  BigDecimal marginPercent;   // Margin: (totalProfit / revenueBeforeTax) × 100
  String paymentMethod;
  PurchaseStatus status;
  String customerId;
  String customerName;
  String customerAddress;
  String customerPhone;
}

