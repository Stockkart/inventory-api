package com.inventory.tax.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Summary of transactions grouped by GST rate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GstRateSummary {
    
    private BigDecimal rate; // GST rate (e.g., 5, 12, 18, 28)
    
    private BigDecimal taxableValue;
    
    private BigDecimal cgstAmount;
    
    private BigDecimal sgstAmount;
    
    private BigDecimal igstAmount;
    
    private BigDecimal cessAmount;
    
    private Long invoiceCount;
}

