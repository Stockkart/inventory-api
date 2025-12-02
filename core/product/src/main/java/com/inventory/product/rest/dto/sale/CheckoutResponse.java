package com.inventory.product.rest.dto.sale;

import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class CheckoutResponse {
  String purchaseId;
  String invoiceId;
  String invoiceNo;
  String businessType;
  String userId;
  String shopId;
  List<PurchaseItem> items;
  BigDecimal subTotal;
  BigDecimal taxTotal;
  BigDecimal discountTotal;
  BigDecimal grandTotal;
  String paymentMethod;
  PurchaseStatus status;
  String customerName;
  String customerAddress;
  String customerPhone;
}

