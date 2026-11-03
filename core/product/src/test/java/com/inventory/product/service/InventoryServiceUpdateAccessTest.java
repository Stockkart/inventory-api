package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.vertical.InventoryVerticalValidationHandler;
import com.inventory.product.validation.InventoryValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceUpdateAccessTest {

  @Mock private InventoryRepository inventoryRepository;
  @Mock private InventoryValidator inventoryValidator;
  @Mock private InventoryVerticalValidationHandler inventoryVerticalValidationHandler;
  @Mock private ProductService productService;
  @InjectMocks private InventoryService inventoryService;

  @Test
  void accessDeniedKeepsItsCodeInsteadOfBecomingAServerError() {
    Inventory inventory = new Inventory();
    inventory.setId("inv-1");
    inventory.setShopId("shop-1");
    when(inventoryRepository.findById("inv-1")).thenReturn(Optional.of(inventory));
    doThrow(new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission to edit rates"))
        .when(inventoryVerticalValidationHandler).validateUpdate(any(), any(), any());

    BaseException e = assertThrows(BaseException.class,
        () -> inventoryService.update("inv-1", new UpdateInventoryRequest(), "shop-1", "user-7"));

    assertEquals(ErrorCode.ACCESS_DENIED, e.getErrorCode());
    verify(inventoryRepository, never()).save(any(Inventory.class));
  }

  /**
   * A pricing 400 raised while the inventory is saved (the write aspect runs the pricing update
   * before the save) stays a 400, and nothing is saved.
   */
  @Test
  void pricingValidationFailureOnSaveStaysABadRequest() {
    Inventory inventory = new Inventory();
    inventory.setId("inv-1");
    inventory.setShopId("shop-1");
    when(inventoryRepository.findById("inv-1")).thenReturn(Optional.of(inventory));
    ValidationException clash = new ValidationException("Rate rate-a differs from rate rule Rate-A only in case");
    when(inventoryRepository.save(any(Inventory.class))).thenThrow(clash);

    ValidationException e = assertThrows(ValidationException.class,
        () -> inventoryService.update("inv-1", new UpdateInventoryRequest(), "shop-1", "user-7"));

    assertEquals(clash.getMessage(), e.getMessage());
  }
}
