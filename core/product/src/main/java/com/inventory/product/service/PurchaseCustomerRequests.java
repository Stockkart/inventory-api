package com.inventory.product.service;

import com.inventory.product.rest.dto.request.AddToCartRequest;
import com.inventory.user.domain.model.enums.CustomerPartyType;
import com.inventory.user.rest.dto.request.CreateCustomerRequest;
import org.springframework.util.StringUtils;

/** Maps cart / quotation customer fields onto {@link CreateCustomerRequest}. */
final class PurchaseCustomerRequests {

  private PurchaseCustomerRequests() {}

  static CreateCustomerRequest fromCart(AddToCartRequest request) {
    CreateCustomerRequest create = new CreateCustomerRequest();
    if (request == null) {
      return create;
    }
    create.setName(request.getCustomerName());
    create.setPhone(request.getCustomerPhone());
    create.setAddress(request.getCustomerAddress());
    create.setEmail(request.getCustomerEmail());
    create.setGstin(request.getCustomerGstin());
    create.setDlNo(request.getCustomerDlNo());
    create.setPan(request.getCustomerPan());
    create.setPartyType(parsePartyType(request.getCustomerPartyType()));
    return create;
  }

  static CustomerPartyType parsePartyType(String raw) {
    if (!StringUtils.hasText(raw)) {
      return CustomerPartyType.CONSUMER;
    }
    try {
      return CustomerPartyType.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      return CustomerPartyType.CONSUMER;
    }
  }

  /** Bill-level display name when using General Customer (or overlay). */
  static String displayNameOverlay(String customerId, AddToCartRequest request) {
    if (request == null) {
      return null;
    }
    if (StringUtils.hasText(request.getCustomerName())) {
      return request.getCustomerName().trim();
    }
    if (StringUtils.hasText(request.getCustomerPhone())) {
      return request.getCustomerPhone().trim();
    }
    return null;
  }

  /**
   * True when the payload is meant to set or clear the cart customer. Item-only upserts omit these
   * fields and must not replace an existing customer with General Customer.
   */
  static boolean hasCustomerPatch(AddToCartRequest request) {
    if (request == null) {
      return false;
    }
    if (request.getCustomerId() != null) {
      return true;
    }
    return StringUtils.hasText(request.getCustomerName())
        || StringUtils.hasText(request.getCustomerPhone())
        || StringUtils.hasText(request.getCustomerEmail())
        || StringUtils.hasText(request.getCustomerAddress())
        || StringUtils.hasText(request.getCustomerGstin())
        || StringUtils.hasText(request.getCustomerDlNo())
        || StringUtils.hasText(request.getCustomerPan())
        || StringUtils.hasText(request.getCustomerPartyType());
  }
}
