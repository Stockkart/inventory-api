package com.inventory.tax.rest.dto;

import com.inventory.tax.domain.model.GstRateSummary;
import com.inventory.tax.domain.model.HsnSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for GST summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GstSummaryResponse {
    
    private String shopId;
    
    private String period;
    
    private String shopGstin;
    
    private String shopName;
    
    // Overall totals
    private BigDecimal totalTaxableValue;
    
    private BigDecimal totalCgst;
    
    private BigDecimal totalSgst;
    
    private BigDecimal totalIgst;
    
    private BigDecimal totalCess;
    
    private BigDecimal totalTaxLiability;
    
    private Long totalInvoices;
    
    // Breakdown by GST rate
    private List<GstRateSummary> ratewiseSummary;
    
    // HSN-wise summary
    private List<HsnSummary> hsnSummary;
}

