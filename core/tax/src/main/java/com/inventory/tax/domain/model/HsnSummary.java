package com.inventory.tax.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * HSN-wise summary for GST returns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HsnSummary {
    
    private String hsnCode;
    
    private String description;
    
    private String uqc; // Unit Quantity Code (e.g., NOS, KGS, MTR)
    
    private BigDecimal totalQuantity;
    
    private BigDecimal totalValue;
    
    private BigDecimal taxableValue;
    
    private BigDecimal cgstRate;
    
    private BigDecimal cgstAmount;
    
    private BigDecimal sgstRate;
    
    private BigDecimal sgstAmount;
    
    private BigDecimal igstRate;
    
    private BigDecimal igstAmount;
}

