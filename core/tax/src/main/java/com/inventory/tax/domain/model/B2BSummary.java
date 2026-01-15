package com.inventory.tax.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * B2B (Business to Business) invoice summary for GSTR-1.
 * Required for invoices where buyer has GSTIN.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class B2BSummary {
    
    private String buyerGstin;
    
    private String buyerName;
    
    private String invoiceNo;
    
    private Instant invoiceDate;
    
    private BigDecimal invoiceValue;
    
    private String placeOfSupply; // State code
    
    private boolean reverseCharge;
    
    private String invoiceType; // Regular, SEZ, Deemed Export
    
    private List<B2BItemDetail> items;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class B2BItemDetail {
        private BigDecimal rate;
        private BigDecimal taxableValue;
        private BigDecimal cgstAmount;
        private BigDecimal sgstAmount;
        private BigDecimal igstAmount;
        private BigDecimal cessAmount;
    }
}

