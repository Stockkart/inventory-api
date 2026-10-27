package com.inventory.product.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.AddToCartRequest;
import org.junit.jupiter.api.Test;

class CheckoutValidatorTest {

  private final CheckoutValidator validator = new CheckoutValidator();

  /** A pick on a line already in the cart sends quantity 0: it needs no quantity. */
  @Test
  void rateNameWithQuantityZeroIsAnUpdateOnlyPick() {
    assertDoesNotThrow(() -> validator.validateCartItem(item(0, 0, "Rate-A")));
    assertDoesNotThrow(() -> validator.validateCartItem(item(null, null, "Rate-A")));
  }

  @Test
  void rateNameWithAQuantityIncreaseIsAllowed() {
    assertDoesNotThrow(() -> validator.validateCartItem(item(1, 10, "Rate-A")));
  }

  /** A "−" tap only takes units off a line: it cannot also pick a rate. */
  @Test
  void rateNameOnAQuantityDecreaseIsRejected() {
    ValidationException e = assertThrows(ValidationException.class,
        () -> validator.validateCartItem(item(-1, -10, "Rate-A")));
    assertTrue(e.getMessage().contains("rateName"), e.getMessage());
    assertThrows(ValidationException.class,
        () -> validator.validateCartItem(item(null, -1, "Rate-A")));
  }

  private static AddToCartRequest.CartItem item(Integer quantity, Integer baseQuantity, String rateName) {
    AddToCartRequest.CartItem item = new AddToCartRequest.CartItem();
    item.setId("lot-1");
    item.setQuantity(quantity);
    item.setBaseQuantity(baseQuantity);
    item.setRateName(rateName);
    return item;
  }
}
