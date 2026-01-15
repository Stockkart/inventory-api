package com.inventory.tax.service;

import com.inventory.tax.domain.model.GstRateSummary;
import com.inventory.tax.domain.model.HsnSummary;
import com.inventory.tax.facade.CustomerDataFacade;
import com.inventory.tax.facade.PurchaseDataFacade;
import com.inventory.tax.facade.PurchaseDataFacade.PurchaseData;
import com.inventory.tax.facade.PurchaseDataFacade.PurchaseItemData;
import com.inventory.tax.facade.ShopDataFacade;
import com.inventory.tax.rest.dto.GstSummaryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for aggregating GST data from purchases.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GstAggregationService {
    
    private final PurchaseDataFacade purchaseDataFacade;
    private final ShopDataFacade shopDataFacade;
    private final CustomerDataFacade customerDataFacade;
    
    /**
     * Generate GST summary for a given period.
     */
    public GstSummaryResponse generateSummary(String shopId, String period) {
        log.info("Generating GST summary for shop {} period {}", shopId, period);
        
        // Parse period (YYYY-MM)
        YearMonth yearMonth = YearMonth.parse(period);
        Instant startDate = yearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endDate = yearMonth.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
        
        // Fetch shop data
        ShopDataFacade.ShopData shopData = shopDataFacade.getShopById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Shop not found: " + shopId));
        
        // Fetch completed purchases for the period
        List<PurchaseData> purchases = purchaseDataFacade.getCompletedPurchases(shopId, startDate, endDate);
        
        log.info("Found {} purchases for period {}", purchases.size(), period);
        
        // Aggregate data
        BigDecimal totalTaxableValue = BigDecimal.ZERO;
        BigDecimal totalCgst = BigDecimal.ZERO;
        BigDecimal totalSgst = BigDecimal.ZERO;
        
        Map<BigDecimal, GstRateSummary> rateSummaryMap = new HashMap<>();
        Map<String, HsnSummary.HsnSummaryBuilder> hsnSummaryMap = new HashMap<>();
        
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
            
            // Process items for rate-wise and HSN-wise summary
            if (purchase.items() != null) {
                for (PurchaseItemData item : purchase.items()) {
                    processItemForRateSummary(item, rateSummaryMap);
                    processItemForHsnSummary(item, hsnSummaryMap);
                }
            }
        }
        
        BigDecimal totalTaxLiability = totalCgst.add(totalSgst);
        
        return GstSummaryResponse.builder()
            .shopId(shopId)
            .period(period)
            .shopGstin(shopData.gstin())
            .shopName(shopData.name())
            .totalTaxableValue(totalTaxableValue.setScale(2, RoundingMode.HALF_UP))
            .totalCgst(totalCgst.setScale(2, RoundingMode.HALF_UP))
            .totalSgst(totalSgst.setScale(2, RoundingMode.HALF_UP))
            .totalIgst(BigDecimal.ZERO) // IGST for interstate - not implemented yet
            .totalCess(BigDecimal.ZERO) // Cess - not implemented yet
            .totalTaxLiability(totalTaxLiability.setScale(2, RoundingMode.HALF_UP))
            .totalInvoices((long) purchases.size())
            .ratewiseSummary(new ArrayList<>(rateSummaryMap.values()))
            .hsnSummary(hsnSummaryMap.values().stream().map(HsnSummary.HsnSummaryBuilder::build).toList())
            .build();
    }
    
    private void processItemForRateSummary(PurchaseItemData item, Map<BigDecimal, GstRateSummary> rateSummaryMap) {
        // Calculate total GST rate (CGST + SGST)
        BigDecimal cgstRate = parseRate(item.cgst());
        BigDecimal sgstRate = parseRate(item.sgst());
        BigDecimal totalRate = cgstRate.add(sgstRate);
        
        // Calculate item taxable value
        BigDecimal taxableValue = item.maximumRetailPrice() != null && item.quantity() != null
            ? item.maximumRetailPrice().multiply(BigDecimal.valueOf(item.quantity()))
            : BigDecimal.ZERO;
        
        // Calculate tax amounts
        BigDecimal cgstAmount = taxableValue.multiply(cgstRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal sgstAmount = taxableValue.multiply(sgstRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        
        GstRateSummary existing = rateSummaryMap.get(totalRate);
        if (existing == null) {
            rateSummaryMap.put(totalRate, GstRateSummary.builder()
                .rate(totalRate)
                .taxableValue(taxableValue)
                .cgstAmount(cgstAmount)
                .sgstAmount(sgstAmount)
                .igstAmount(BigDecimal.ZERO)
                .cessAmount(BigDecimal.ZERO)
                .invoiceCount(1L)
                .build());
        } else {
            existing.setTaxableValue(existing.getTaxableValue().add(taxableValue));
            existing.setCgstAmount(existing.getCgstAmount().add(cgstAmount));
            existing.setSgstAmount(existing.getSgstAmount().add(sgstAmount));
            existing.setInvoiceCount(existing.getInvoiceCount() + 1);
        }
    }
    
    private void processItemForHsnSummary(PurchaseItemData item, Map<String, HsnSummary.HsnSummaryBuilder> hsnSummaryMap) {
        String hsn = item.hsn() != null ? item.hsn() : "UNKNOWN";
        
        BigDecimal taxableValue = item.maximumRetailPrice() != null && item.quantity() != null
            ? item.maximumRetailPrice().multiply(BigDecimal.valueOf(item.quantity()))
            : BigDecimal.ZERO;
        
        BigDecimal cgstRate = parseRate(item.cgst());
        BigDecimal sgstRate = parseRate(item.sgst());
        BigDecimal cgstAmount = taxableValue.multiply(cgstRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal sgstAmount = taxableValue.multiply(sgstRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        
        HsnSummary.HsnSummaryBuilder builder = hsnSummaryMap.get(hsn);
        if (builder == null) {
            builder = HsnSummary.builder()
                .hsnCode(hsn)
                .description(item.name())
                .uqc("NOS") // Default unit
                .totalQuantity(BigDecimal.valueOf(item.quantity() != null ? item.quantity() : 0))
                .totalValue(taxableValue)
                .taxableValue(taxableValue)
                .cgstRate(cgstRate)
                .cgstAmount(cgstAmount)
                .sgstRate(sgstRate)
                .sgstAmount(sgstAmount)
                .igstRate(BigDecimal.ZERO)
                .igstAmount(BigDecimal.ZERO);
            hsnSummaryMap.put(hsn, builder);
        } else {
            HsnSummary existing = builder.build();
            builder.totalQuantity(existing.getTotalQuantity().add(BigDecimal.valueOf(item.quantity() != null ? item.quantity() : 0)))
                .totalValue(existing.getTotalValue().add(taxableValue))
                .taxableValue(existing.getTaxableValue().add(taxableValue))
                .cgstAmount(existing.getCgstAmount().add(cgstAmount))
                .sgstAmount(existing.getSgstAmount().add(sgstAmount));
        }
    }
    
    private BigDecimal parseRate(String rate) {
        if (rate == null || rate.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(rate.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
    
    /**
     * Get date range for a period.
     */
    public DateRange getDateRange(String period) {
        YearMonth yearMonth = YearMonth.parse(period);
        Instant startDate = yearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endDate = yearMonth.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
        return new DateRange(startDate, endDate);
    }
    
    public record DateRange(Instant startDate, Instant endDate) {}
}

