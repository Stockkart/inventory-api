package com.inventory.tax.domain.model;

/**
 * Status of a GST return filing.
 */
public enum GstReturnStatus {
    DRAFT,       // Return is being prepared
    GENERATED,   // Return data has been generated
    EXPORTED,    // JSON has been exported for filing
    FILED,       // Return has been filed on GST portal
    AMENDED      // Return has been amended
}

