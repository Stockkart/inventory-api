package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.sale.CheckoutRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class CheckoutValidator {

  private static final int MAX_QUANTITY = 1000;
  private static final int MAX_ITEMS_PER_SALE = 100;

  public void validateCheckoutRequest(CheckoutRequest request) {
    if (request == null) {
      throw new ValidationException("Checkout request cannot be null");
    }
    if (!StringUtils.hasText(request.getShopId())) {
      throw new ValidationException("Shop ID is required");
    }
    if (!StringUtils.hasText(request.getUserId())) {
      throw new ValidationException("User ID is required");
    }
    if (CollectionUtils.isEmpty(request.getItems())) {
      throw new ValidationException("At least one item is required for checkout");
    }
    if (request.getItems().size() > MAX_ITEMS_PER_SALE) {
      throw new ValidationException("Exceeded maximum number of items per sale (" + MAX_ITEMS_PER_SALE + ")");
    }
  }

  public void validateCheckoutItem(CheckoutRequest.CheckoutItem item) {
    if (item.getQty() == null || item.getQty() <= 0) {
      throw new ValidationException("Invalid quantity for item: " + item.getBarcode());
    }
    if (item.getQty() > MAX_QUANTITY) {
      throw new ValidationException("Maximum quantity per item is " + MAX_QUANTITY);
    }
  }
}
