package com.inventory.tax.facade;

import java.time.Instant;
import java.util.List;

/**
 * Facade interface for accessing purchase data from the product module.
 * This abstraction allows the tax module to fetch data without direct DB access.
 */
public interface PurchaseDataFacade {
    
    /**
     * Get all completed purchases for a shop within a date range.
     */
    List<PurchaseData> getCompletedPurchases(String shopId, Instant startDate, Instant endDate);
    
    /**
     * Get purchase count for a period.
     */
    long getPurchaseCount(String shopId, Instant startDate, Instant endDate);
    
    /**
     * Get invoice number range for a period.
     */
    InvoiceRange getInvoiceRange(String shopId, Instant startDate, Instant endDate);
    
    /**
     * Data transfer object for purchase information.
     */
    record PurchaseData(
        String id,
        String invoiceNo,
        String customerId,
        String customerGstin,
        String customerName,
        String businessType,
        java.math.BigDecimal subTotal,
        java.math.BigDecimal taxTotal,
        java.math.BigDecimal sgstAmount,
        java.math.BigDecimal cgstAmount,
        java.math.BigDecimal grandTotal,
        Instant soldAt,
        List<PurchaseItemData> items
    ) {}
    
    /**
     * Data transfer object for purchase item information.
     */
    record PurchaseItemData(
        String inventoryId,
        String name,
        String hsn,
        Integer quantity,
        java.math.BigDecimal maximumRetailPrice,
        java.math.BigDecimal sellingPrice,
        String sgst,
        String cgst
    ) {}
    
    /**
     * Invoice range for document summary.
     */
    record InvoiceRange(
        String fromInvoiceNo,
        String toInvoiceNo,
        long totalCount,
        long cancelledCount
    ) {}
}

