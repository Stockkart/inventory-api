package com.inventory.tax.facade.impl;

import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.PurchaseStatus;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.tax.facade.PurchaseDataFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Implementation of PurchaseDataFacade using the product module's repository.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurchaseDataFacadeImpl implements PurchaseDataFacade {
    
    private final PurchaseRepository purchaseRepository;
    
    @Override
    public List<PurchaseData> getCompletedPurchases(String shopId, Instant startDate, Instant endDate) {
        log.debug("Fetching completed purchases for shop {} from {} to {}", shopId, startDate, endDate);
        
        List<Purchase> purchases = purchaseRepository.findByShopIdAndStatusAndSoldAtBetween(
            shopId, PurchaseStatus.COMPLETED, startDate, endDate
        );
        
        return purchases.stream()
            .map(this::toPurchaseData)
            .toList();
    }
    
    @Override
    public long getPurchaseCount(String shopId, Instant startDate, Instant endDate) {
        return purchaseRepository.countByShopIdAndStatusAndSoldAtBetween(
            shopId, PurchaseStatus.COMPLETED, startDate, endDate
        );
    }
    
    @Override
    public InvoiceRange getInvoiceRange(String shopId, Instant startDate, Instant endDate) {
        List<Purchase> purchases = purchaseRepository.findByShopIdAndStatusAndSoldAtBetween(
            shopId, PurchaseStatus.COMPLETED, startDate, endDate
        );
        
        if (purchases.isEmpty()) {
            return new InvoiceRange(null, null, 0, 0);
        }
        
        List<Purchase> sorted = purchases.stream()
            .sorted(Comparator.comparing(Purchase::getInvoiceNo, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        
        String fromInvoice = sorted.get(0).getInvoiceNo();
        String toInvoice = sorted.get(sorted.size() - 1).getInvoiceNo();
        
        // Count cancelled purchases
        long cancelledCount = purchaseRepository.countByShopIdAndStatusAndSoldAtBetween(
            shopId, PurchaseStatus.CANCELLED, startDate, endDate
        );
        
        return new InvoiceRange(fromInvoice, toInvoice, purchases.size(), cancelledCount);
    }
    
    private PurchaseData toPurchaseData(Purchase purchase) {
        List<PurchaseItemData> items = new ArrayList<>();
        if (purchase.getItems() != null) {
            items = purchase.getItems().stream()
                .map(this::toPurchaseItemData)
                .toList();
        }
        
        return new PurchaseData(
            purchase.getId(),
            purchase.getInvoiceNo(),
            purchase.getCustomerId(),
            null, // customerGstin - will be fetched via CustomerDataFacade
            purchase.getCustomerName(),
            purchase.getBusinessType(),
            purchase.getSubTotal(),
            purchase.getTaxTotal(),
            purchase.getSgstAmount(),
            purchase.getCgstAmount(),
            purchase.getGrandTotal(),
            purchase.getSoldAt(),
            items
        );
    }
    
    private PurchaseItemData toPurchaseItemData(PurchaseItem item) {
        return new PurchaseItemData(
            item.getInventoryId(),
            item.getName(),
            null, // hsn - not stored in PurchaseItem, would need to fetch from Inventory
            item.getQuantity(),
            item.getMaximumRetailPrice(),
            item.getSellingPrice(),
            item.getSgst(),
            item.getCgst()
        );
    }
}

