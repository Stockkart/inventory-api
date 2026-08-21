package com.inventory.product.domain.model;

import com.inventory.product.domain.model.enums.DocumentType;
import com.inventory.product.domain.model.enums.EstimateState;

/** Null-safe helpers for {@link Purchase#getDocumentType()} (legacy rows default to SALE). */
public final class DocumentTypes {

  private DocumentTypes() {}

  public static DocumentType resolve(Purchase purchase) {
    if (purchase == null || purchase.getDocumentType() == null) {
      return DocumentType.SALE;
    }
    return purchase.getDocumentType();
  }

  public static boolean isEstimate(Purchase purchase) {
    return resolve(purchase) == DocumentType.ESTIMATE;
  }

  public static boolean isOpenEstimate(Purchase purchase) {
    return isEstimate(purchase) && purchase.getEstimateState() == EstimateState.OPEN;
  }

  public static boolean isSaleDocument(Purchase purchase) {
    return resolve(purchase) == DocumentType.SALE;
  }
}
