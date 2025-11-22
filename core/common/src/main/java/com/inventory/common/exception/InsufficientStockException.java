package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;

/**
 * Exception thrown when there is insufficient stock to complete an operation.
 */
public class InsufficientStockException extends BaseException {

  private final String productId;
  private final Integer availableQuantity;
  private final Integer requestedQuantity;

  /**
   * Constructs a new InsufficientStockException with the specified detail message.
   *
   * @param message           the detail message
   * @param productId         the ID of the product with insufficient stock
   * @param availableQuantity the available quantity in stock
   * @param requestedQuantity the quantity that was requested
   */
  public InsufficientStockException(String message, String productId,
                                    Integer availableQuantity, Integer requestedQuantity) {
    super(ErrorCode.INSUFFICIENT_STOCK, message);
    this.productId = productId;
    this.availableQuantity = availableQuantity;
    this.requestedQuantity = requestedQuantity;
  }

  /**
   * Gets the ID of the product with insufficient stock.
   *
   * @return the product ID
   */
  public String getProductId() {
    return productId;
  }

  /**
   * Gets the available quantity in stock.
   *
   * @return the available quantity
   */
  public Integer getAvailableQuantity() {
    return availableQuantity;
  }

  /**
   * Gets the quantity that was requested.
   *
   * @return the requested quantity
   */
  public Integer getRequestedQuantity() {
    return requestedQuantity;
  }

  @Override
  public String getMessage() {
    return String.format("%s (Product ID: %s, Available: %d, Requested: %d)",
            super.getMessage(), productId, availableQuantity, requestedQuantity);
  }
}
