package com.inventory.tax.service;

import com.inventory.tax.domain.model.B2BSummary;
import com.inventory.tax.domain.model.HsnSummary;
import com.inventory.tax.facade.CustomerDataFacade;
import com.inventory.tax.facade.PurchaseDataFacade;
import com.inventory.tax.facade.PurchaseDataFacade.InvoiceRange;
import com.inventory.tax.facade.PurchaseDataFacade.PurchaseData;
import com.inventory.tax.facade.PurchaseDataFacade.PurchaseItemData;
import com.inventory.tax.facade.ShopDataFacade;
import com.inventory.tax.rest.dto.Gstr1Response;
import com.inventory.tax.rest.dto.Gstr1Response.B2CLargeSummary;
import com.inventory.tax.rest.dto.Gstr1Response.B2CSmallSummary;
import com.inventory.tax.rest.dto.Gstr1Response.DocumentSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for generating GSTR-1 (Outward Supplies) report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Gstr1Service {
    
    private static final BigDecimal B2CL_THRESHOLD = new BigDecimal("250000"); // 2.5 Lakhs
    
    private final PurchaseDataFacade purchaseDataFacade;
    private final ShopDataFacade shopDataFacade;
    private final CustomerDataFacade customerDataFacade;
    private final GstAggregationService aggregationService;
    
    /**
     * Generate GSTR-1 report for a given period.
     */
    public Gstr1Response generateGstr1(String shopId, String period) {
        log.info("Generating GSTR-1 for shop {} period {}", shopId, period);
        
        // Get shop data
        ShopDataFacade.ShopData shopData = shopDataFacade.getShopById(shopId)
            .orElseThrow(() -> new IllegalArgumentException("Shop not found: " + shopId));
        
        // Get date range
        GstAggregationService.DateRange dateRange = aggregationService.getDateRange(period);
        
        // Fetch purchases
        List<PurchaseData> purchases = purchaseDataFacade.getCompletedPurchases(
            shopId, dateRange.startDate(), dateRange.endDate()
        );
        
        // Categorize purchases
        List<B2BSummary> b2bInvoices = new ArrayList<>();
        List<B2CLargeSummary> b2clInvoices = new ArrayList<>();
        BigDecimal b2csTaxableValue = BigDecimal.ZERO;
        BigDecimal b2csCgst = BigDecimal.ZERO;
        BigDecimal b2csSgst = BigDecimal.ZERO;
        
        Map<String, HsnSummary.HsnSummaryBuilder> hsnMap = new HashMap<>();
        
        for (PurchaseData purchase : purchases) {
            // Check if B2B (customer has GSTIN)
            String customerGstin = getCustomerGstin(purchase.customerId());
            
            if (StringUtils.hasText(customerGstin)) {
                // B2B Invoice
                b2bInvoices.add(createB2BSummary(purchase, customerGstin));
            } else if (purchase.grandTotal() != null && purchase.grandTotal().compareTo(B2CL_THRESHOLD) > 0) {
                // B2C Large (Interstate > 2.5L) - simplified, assuming all are intrastate for now
                // In real implementation, need to check if interstate
                b2clInvoices.add(createB2CLargeSummary(purchase, shopData.state()));
            } else {
                // B2C Small
                b2csTaxableValue = b2csTaxableValue.add(
                    purchase.subTotal() != null ? purchase.subTotal() : BigDecimal.ZERO
                );
                b2csCgst = b2csCgst.add(
                    purchase.cgstAmount() != null ? purchase.cgstAmount() : BigDecimal.ZERO
                );
                b2csSgst = b2csSgst.add(
                    purchase.sgstAmount() != null ? purchase.sgstAmount() : BigDecimal.ZERO
                );
            }
            
            // Process HSN summary
            if (purchase.items() != null) {
                for (PurchaseItemData item : purchase.items()) {
                    processHsnSummary(item, hsnMap);
                }
            }
        }
        
        // Get invoice range for document summary
        InvoiceRange invoiceRange = purchaseDataFacade.getInvoiceRange(
            shopId, dateRange.startDate(), dateRange.endDate()
        );
        
        // Format period as MMYYYY
        YearMonth yearMonth = YearMonth.parse(period);
        String formattedPeriod = String.format("%02d%d", yearMonth.getMonthValue(), yearMonth.getYear());
        
        return Gstr1Response.builder()
            .shopId(shopId)
            .gstin(shopData.gstin())
            .period(formattedPeriod)
            .legalName(shopData.name())
            .b2bInvoices(b2bInvoices)
            .b2clInvoices(b2clInvoices)
            .b2csSummary(B2CSmallSummary.builder()
                .placeOfSupply(shopData.stateCode())
                .rate(BigDecimal.valueOf(18)) // Default rate - should be calculated
                .taxableValue(b2csTaxableValue.setScale(2, RoundingMode.HALF_UP))
                .cgstAmount(b2csCgst.setScale(2, RoundingMode.HALF_UP))
                .sgstAmount(b2csSgst.setScale(2, RoundingMode.HALF_UP))
                .cessAmount(BigDecimal.ZERO)
                .build())
            .hsnSummary(hsnMap.values().stream().map(HsnSummary.HsnSummaryBuilder::build).toList())
            .documentSummary(DocumentSummary.builder()
                .totalInvoicesIssued(invoiceRange.totalCount())
                .fromInvoiceNo(invoiceRange.fromInvoiceNo())
                .toInvoiceNo(invoiceRange.toInvoiceNo())
                .cancelledCount(invoiceRange.cancelledCount())
                .build())
            .build();
    }
    
    private String getCustomerGstin(String customerId) {
        if (!StringUtils.hasText(customerId)) {
            return null;
        }
        return customerDataFacade.getCustomerById(customerId)
            .map(CustomerDataFacade.CustomerData::gstin)
            .orElse(null);
    }
    
    private B2BSummary createB2BSummary(PurchaseData purchase, String buyerGstin) {
        List<B2BSummary.B2BItemDetail> itemDetails = new ArrayList<>();
        
        if (purchase.items() != null) {
            // Group by rate
            Map<BigDecimal, B2BSummary.B2BItemDetail> rateMap = new HashMap<>();
            for (PurchaseItemData item : purchase.items()) {
                BigDecimal rate = parseRate(item.cgst()).add(parseRate(item.sgst()));
                BigDecimal taxableValue = item.maximumRetailPrice() != null && item.quantity() != null
                    ? item.maximumRetailPrice().multiply(BigDecimal.valueOf(item.quantity()))
                    : BigDecimal.ZERO;
                BigDecimal cgstAmount = taxableValue.multiply(parseRate(item.cgst()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                BigDecimal sgstAmount = taxableValue.multiply(parseRate(item.sgst()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
                
                B2BSummary.B2BItemDetail existing = rateMap.get(rate);
                if (existing == null) {
                    rateMap.put(rate, B2BSummary.B2BItemDetail.builder()
                        .rate(rate)
                        .taxableValue(taxableValue)
                        .cgstAmount(cgstAmount)
                        .sgstAmount(sgstAmount)
                        .igstAmount(BigDecimal.ZERO)
                        .cessAmount(BigDecimal.ZERO)
                        .build());
                } else {
                    existing.setTaxableValue(existing.getTaxableValue().add(taxableValue));
                    existing.setCgstAmount(existing.getCgstAmount().add(cgstAmount));
                    existing.setSgstAmount(existing.getSgstAmount().add(sgstAmount));
                }
            }
            itemDetails.addAll(rateMap.values());
        }
        
        String customerName = customerDataFacade.getCustomerById(purchase.customerId())
            .map(CustomerDataFacade.CustomerData::name)
            .orElse(purchase.customerName());
        
        return B2BSummary.builder()
            .buyerGstin(buyerGstin)
            .buyerName(customerName)
            .invoiceNo(purchase.invoiceNo())
            .invoiceDate(purchase.soldAt())
            .invoiceValue(purchase.grandTotal())
            .placeOfSupply(null) // Would need customer state
            .reverseCharge(false)
            .invoiceType("Regular")
            .items(itemDetails)
            .build();
    }
    
    private B2CLargeSummary createB2CLargeSummary(PurchaseData purchase, String shopState) {
        BigDecimal rate = BigDecimal.valueOf(18); // Default
        if (purchase.items() != null && !purchase.items().isEmpty()) {
            PurchaseItemData firstItem = purchase.items().get(0);
            rate = parseRate(firstItem.cgst()).add(parseRate(firstItem.sgst()));
        }
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(ZoneId.systemDefault());
        
        return B2CLargeSummary.builder()
            .placeOfSupply(shopState)
            .invoiceNo(purchase.invoiceNo())
            .invoiceDate(purchase.soldAt() != null ? formatter.format(purchase.soldAt()) : null)
            .invoiceValue(purchase.grandTotal())
            .rate(rate)
            .taxableValue(purchase.subTotal())
            .igstAmount(BigDecimal.ZERO) // For interstate
            .cessAmount(BigDecimal.ZERO)
            .build();
    }
    
    private void processHsnSummary(PurchaseItemData item, Map<String, HsnSummary.HsnSummaryBuilder> hsnMap) {
        String hsn = item.hsn() != null ? item.hsn() : "UNKNOWN";
        
        BigDecimal taxableValue = item.maximumRetailPrice() != null && item.quantity() != null
            ? item.maximumRetailPrice().multiply(BigDecimal.valueOf(item.quantity()))
            : BigDecimal.ZERO;
        
        BigDecimal cgstRate = parseRate(item.cgst());
        BigDecimal sgstRate = parseRate(item.sgst());
        BigDecimal cgstAmount = taxableValue.multiply(cgstRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal sgstAmount = taxableValue.multiply(sgstRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        
        HsnSummary.HsnSummaryBuilder builder = hsnMap.get(hsn);
        if (builder == null) {
            hsnMap.put(hsn, HsnSummary.builder()
                .hsnCode(hsn)
                .description(item.name())
                .uqc("NOS")
                .totalQuantity(BigDecimal.valueOf(item.quantity() != null ? item.quantity() : 0))
                .totalValue(taxableValue)
                .taxableValue(taxableValue)
                .cgstRate(cgstRate)
                .cgstAmount(cgstAmount)
                .sgstRate(sgstRate)
                .sgstAmount(sgstAmount)
                .igstRate(BigDecimal.ZERO)
                .igstAmount(BigDecimal.ZERO));
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
}

