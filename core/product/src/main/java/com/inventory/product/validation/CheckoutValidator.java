package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.sale.AddToCartRequest;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

@Component
public class CheckoutValidator {

  private static final int MAX_QUANTITY = 1000;
  private static final int MAX_ITEMS_PER_SALE = 100;

  public void validateCheckoutItem(CheckoutRequest.CheckoutItem item) {
    if (!StringUtils.hasText(item.getLotId())) {
      throw new ValidationException("Lot ID is required for item");
    }
    if (item.getQuantity() == null || item.getQuantity() <= 0) {
      throw new ValidationException("Invalid quantity for item: " + item.getLotId());
    }
    if (item.getQuantity() > MAX_QUANTITY) {
      throw new ValidationException("Maximum quantity per item is " + MAX_QUANTITY);
    }
    if (item.getSellingPrice() == null || item.getSellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new ValidationException("Selling price must be greater than zero for item: " + item.getLotId());
    }
  }

  public void validateShopIdAndUserId(String shopId, String userId) {
    if (shopId == null || userId == null) {
      throw new ValidationException("Shop ID and User ID are required. Please ensure you are authenticated.");
    }
  }

  public void validateAddToCartRequest(AddToCartRequest request) {
    if (request == null) {
      throw new ValidationException("Add to cart request cannot be null");
    }
    if (!StringUtils.hasText(request.getBusinessType())) {
      throw new ValidationException("Business type is required");
    }
    if (request.getItems().size() > MAX_ITEMS_PER_SALE) {
      throw new ValidationException("Exceeded maximum number of items per sale (" + MAX_ITEMS_PER_SALE + ")");
    }
  }

  public void validateCartItem(AddToCartRequest.CartItem item) {
    if (!StringUtils.hasText(item.getLotId())) {
      throw new ValidationException("Lot ID is required for item");
    }
    if (item.getQuantity() == null || item.getQuantity() == 0) {
      throw new ValidationException("Quantity cannot be zero for item: " + item.getLotId());
    }
    if (Math.abs(item.getQuantity()) > MAX_QUANTITY) {
      throw new ValidationException("Maximum quantity per item is " + MAX_QUANTITY);
    }
    // Selling price is only required for positive quantities (adding items)
    if (item.getQuantity() > 0) {
      if (item.getSellingPrice() == null || item.getSellingPrice().compareTo(BigDecimal.ZERO) <= 0) {
        throw new ValidationException("Selling price must be greater than zero for item: " + item.getLotId());
      }
    }
  }
}
