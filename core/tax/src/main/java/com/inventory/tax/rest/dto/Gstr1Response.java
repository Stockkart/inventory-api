package com.inventory.tax.rest.dto;

import com.inventory.tax.domain.model.B2BSummary;
import com.inventory.tax.domain.model.HsnSummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for GSTR-1 report (Outward Supplies).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Gstr1Response {
    
    private String shopId;
    
    private String gstin;
    
    private String period; // Format: MMYYYY
    
    private String legalName;
    
    // B2B Invoices (where buyer has GSTIN)
    private List<B2BSummary> b2bInvoices;
    
    // B2C Large (Interstate B2C > 2.5L)
    private List<B2CLargeSummary> b2clInvoices;
    
    // B2C Small (Intrastate B2C and Interstate B2C <= 2.5L)
    private B2CSmallSummary b2csSummary;
    
    // HSN Summary
    private List<HsnSummary> hsnSummary;
    
    // Document Summary
    private DocumentSummary documentSummary;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class B2CLargeSummary {
        private String placeOfSupply;
        private String invoiceNo;
        private String invoiceDate;
        private BigDecimal invoiceValue;
        private BigDecimal rate;
        private BigDecimal taxableValue;
        private BigDecimal igstAmount;
        private BigDecimal cessAmount;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class B2CSmallSummary {
        private String placeOfSupply;
        private BigDecimal rate;
        private BigDecimal taxableValue;
        private BigDecimal cgstAmount;
        private BigDecimal sgstAmount;
        private BigDecimal cessAmount;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentSummary {
        private Long totalInvoicesIssued;
        private String fromInvoiceNo;
        private String toInvoiceNo;
        private Long cancelledCount;
    }
}

