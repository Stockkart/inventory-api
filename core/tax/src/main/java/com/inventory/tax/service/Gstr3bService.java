package com.inventory.tax.service;

import com.inventory.tax.facade.PurchaseDataFacade;
import com.inventory.tax.facade.PurchaseDataFacade.PurchaseData;
import com.inventory.tax.facade.ShopDataFacade;
import com.inventory.tax.rest.dto.Gstr3bResponse;
import com.inventory.tax.rest.dto.Gstr3bResponse.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for generating GSTR-3B (Summary Return) report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Gstr3bService {
    
    private final PurchaseDataFacade purchaseDataFacade;
    private final ShopDataFacade shopDataFacade;
    private final GstAggregationService aggregationService;
    
    /**
     * Generate GSTR-3B report for a given period.
     */
    public Gstr3bResponse generateGstr3b(String shopId, String period) {
        log.info("Generating GSTR-3B for shop {} period {}", shopId, period);
        
        // Get shop data
        ShopDataFacade.ShopData shopData = shopDataFacade.getShopById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Shop not found: " + shopId));
        
        // Get date range
        GstAggregationService.DateRange dateRange = aggregationService.getDateRange(period);
        
        // Fetch purchases
        List<PurchaseData> purchases = purchaseDataFacade.getCompletedPurchases(
            shopId, dateRange.startDate(), dateRange.endDate()
        );
        
        // Calculate totals
        BigDecimal totalTaxableValue = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        
        for (PurchaseData purchase : purchases) {
            totalTaxableValue = totalTaxableValue.add(
                purchase.subTotal() != null ? purchase.subTotal() : BigDecimal.ZERO
            );
            totalCgst = totalCgst.add(
                purchase.cgstAmount() != null ? purchase.cgstAmount() : BigDecimal.ZERO
            );
            totalSgst = totalSgst.add(
                purchase.sgstAmount() != null ? purchase.sgstAmount() : BigDecimal.ZERO
            );
        }
        
        // Format period as MMYYYY
        YearMonth yearMonth = YearMonth.parse(period);
        String formattedPeriod = String.format("%02d%d", yearMonth.getMonthValue(), yearMonth.getYear());
        
        // Build 3.1 - Outward Supplies
        OutwardSupplies outwardSupplies = OutwardSupplies.builder()
            .taxableSupplies(SupplyDetail.builder()
                .taxableValue(totalTaxableValue.setScale(2, RoundingMode.HALF_UP))
                .igst(BigDecimal.ZERO)
                .cgst(totalCgst.setScale(2, RoundingMode.HALF_UP))
                .sgst(totalSgst.setScale(2, RoundingMode.HALF_UP))
                .cess(BigDecimal.ZERO)
                .build())
            .zeroRatedSupplies(createEmptySupplyDetail())
            .nilRatedSupplies(createEmptySupplyDetail())
            .reverseChargeSupplies(createEmptySupplyDetail())
            .nonGstSupplies(createEmptySupplyDetail())
            .build();
        
        // Build 4 - Input Tax Credit (placeholder - would need purchase invoices)
        InputTaxCredit inputTaxCredit = InputTaxCredit.builder()
            .itcAvailable(createEmptySupplyDetail())
            .itcReversed(createEmptySupplyDetail())
            .netItc(createEmptySupplyDetail())
            .ineligibleItc(createEmptySupplyDetail())
            .build();
        
        // Build 5 - Exempt Supplies
        ExemptSupplies exemptSupplies = ExemptSupplies.builder()
            .interStateSupplies(BigDecimal.ZERO)
            .intraStateSupplies(BigDecimal.ZERO)
            .build();
        
        // Build 6.1 - Tax Payment
        BigDecimal totalPayable = totalCgst.add(totalSgst);
        TaxPayment taxPayment = TaxPayment.builder()
            .igstPayable(BigDecimal.ZERO)
            .cgstPayable(totalCgst.setScale(2, RoundingMode.HALF_UP))
            .sgstPayable(totalSgst.setScale(2, RoundingMode.HALF_UP))
            .cessPayable(BigDecimal.ZERO)
            .totalPayable(totalPayable.setScale(2, RoundingMode.HALF_UP))
            .build();
        
        return Gstr3bResponse.builder()
            .shopId(shopId)
            .gstin(shopData.gstin())
            .period(formattedPeriod)
            .legalName(shopData.name())
            .outwardSupplies(outwardSupplies)
            .interstateSupplies(new ArrayList<>()) // Would need interstate transaction data
            .inputTaxCredit(inputTaxCredit)
            .exemptSupplies(exemptSupplies)
            .taxPayment(taxPayment)
            .build();
    }
    
    private SupplyDetail createEmptySupplyDetail() {
        return SupplyDetail.builder()
            .taxableValue(BigDecimal.ZERO)
            .igst(BigDecimal.ZERO)
            .cgst(BigDecimal.ZERO)
            .sgst(BigDecimal.ZERO)
            .cess(BigDecimal.ZERO)
            .build();
    }
}

