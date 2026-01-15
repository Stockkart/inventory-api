package com.inventory.tax.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for GSTR-3B report (Summary Return).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Gstr3bResponse {
    
    private String shopId;
    
    private String gstin;
    
    private String period; // Format: MMYYYY
    
    private String legalName;
    
    // 3.1 - Details of Outward Supplies
    private OutwardSupplies outwardSupplies;
    
    // 3.2 - Interstate supplies to unregistered persons
    private List<InterstateSupply> interstateSupplies;
    
    // 4 - Eligible ITC (Input Tax Credit) - Placeholder for future
    private InputTaxCredit inputTaxCredit;
    
    // 5 - Exempt, Nil and Non-GST supplies
    private ExemptSupplies exemptSupplies;
    
    // 6.1 - Payment of Tax
    private TaxPayment taxPayment;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutwardSupplies {
        // (a) Outward taxable supplies (other than zero rated, nil rated and exempted)
        private SupplyDetail taxableSupplies;
        
        // (b) Outward taxable supplies (zero rated)
        private SupplyDetail zeroRatedSupplies;
        
        // (c) Other outward supplies (Nil rated, exempted)
        private SupplyDetail nilRatedSupplies;
        
        // (d) Inward supplies (liable to reverse charge)
        private SupplyDetail reverseChargeSupplies;
        
        // (e) Non-GST outward supplies
        private SupplyDetail nonGstSupplies;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplyDetail {
        private BigDecimal taxableValue;
        private BigDecimal igst;
        private BigDecimal cgst;
        private BigDecimal sgst;
        private BigDecimal cess;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InterstateSupply {
        private String placeOfSupply; // State code
        private BigDecimal taxableValue;
        private BigDecimal igstAmount;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputTaxCredit {
        private SupplyDetail itcAvailable;
        private SupplyDetail itcReversed;
        private SupplyDetail netItc;
        private SupplyDetail ineligibleItc;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExemptSupplies {
        private BigDecimal interStateSupplies;
        private BigDecimal intraStateSupplies;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaxPayment {
        private BigDecimal igstPayable;
        private BigDecimal cgstPayable;
        private BigDecimal sgstPayable;
        private BigDecimal cessPayable;
        private BigDecimal totalPayable;
    }
}

