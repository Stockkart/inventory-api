package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.request.CreatePrintJobRequest;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import com.inventory.product.rest.dto.request.ReportPrintOutcomeRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PrintJobValidator {

  public void validateBridgeObservation(PrintBridgeObservation observation) {
    if (observation == null) {
      throw new ValidationException("Print bridge observation is required");
    }
  }

  public void validateCreateRequest(CreatePrintJobRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getSource() == null) {
      throw new ValidationException("Document source is required");
    }
    if (!StringUtils.hasText(request.getDocumentId())) {
      throw new ValidationException("Document ID is required");
    }
    validateBridgeObservation(request.getBridge());
  }

  public void validateOutcomeRequest(ReportPrintOutcomeRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (request.getObservation() == null) {
      throw new ValidationException("Print observation is required");
    }
  }
}
