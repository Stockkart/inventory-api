package com.inventory.product.rest.dto.sale;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
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
  String invoiceId;
  String invoiceNo;
  String businessType;
  String userId;
  String shopId;
  List<PurchaseItem> items;
  BigDecimal subTotal;
  BigDecimal taxTotal;
  BigDecimal sgstAmount; // Calculated SGST amount
  BigDecimal cgstAmount; // Calculated CGST amount
  BigDecimal discountTotal;
  BigDecimal additionalDiscountTotal; // Total additional discount amount
  BigDecimal grandTotal;
  PurchaseStatus status;
  String customerId;
  String customerName;
  String customerAddress;
  String customerPhone;
  String customerGstin;
  String customerDlNo;
  String customerPan;
  String paymentMethod;
}

