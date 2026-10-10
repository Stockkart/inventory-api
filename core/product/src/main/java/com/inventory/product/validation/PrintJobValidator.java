package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import org.springframework.stereotype.Component;

@Component
public class PrintJobValidator {

  public void validateBridgeObservation(PrintBridgeObservation observation) {
    if (observation == null) {
      throw new ValidationException("Print bridge observation is required");
    }
  }
}
