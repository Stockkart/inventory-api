package com.inventory.analytics.utils;

import com.inventory.analytics.rest.dto.response.InventoryAnalyticsDto;
import com.inventory.analytics.rest.dto.response.InventorySummaryDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class InventoryAnalyticsUtils {

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private PurchaseRepository purchaseRepository;

  public List<InventoryAnalyticsDto> calculateInventoryAnalytics(
      String shopId,
      List<Inventory> allInventories,
      Integer lowStockThreshold,
      Integer deadStockDays,
      Integer expiringSoonDays) {

    Map<String, Instant> lastSoldDates = getLastSoldDates(shopId, allInventories);
    Instant now = Instant.now();

    return allInventories.stream()
        .map(inv -> {
          InventoryAnalyticsDto dto = new InventoryAnalyticsDto();
          dto.setInventoryId(inv.getId());
          dto.setLotId(inv.getLotId());
          dto.setBarcode(inv.getBarcode());
          dto.setProductName(inv.getName());
          dto.setCompanyName(inv.getCompanyName());
          dto.setBusinessType(inv.getBusinessType());
          dto.setLocation(inv.getLocation());

          Integer received = getReceivedBaseCount(inv);
          Integer sold = getSoldBaseCount(inv);
          Integer current = getCurrentBaseCount(inv);
          dto.setReceivedCount(received);
          dto.setSoldCount(sold);
          dto.setCurrentCount(current);

          BigDecimal stockPercentage = BigDecimal.ZERO;
          if (received > 0) {
            stockPercentage = BigDecimal.valueOf(current)
                .divide(BigDecimal.valueOf(received), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }
          dto.setStockPercentage(stockPercentage);

          boolean isLowStock = false;
          if (lowStockThreshold != null && current <= lowStockThreshold) {
            isLowStock = true;
          } else if (lowStockThreshold == null && received > 0) {
            isLowStock = stockPercentage.compareTo(BigDecimal.valueOf(20)) < 0;
          }
          dto.setIsLowStock(isLowStock);

          if (inv.getReceivedDate() != null) {
            long daysSinceReceived = ChronoUnit.DAYS.between(inv.getReceivedDate(), now);
            dto.setDaysSinceReceived(daysSinceReceived);
          }
          dto.setReceivedDate(inv.getReceivedDate());

          if (inv.getExpiryDate() != null) {
            long daysUntilExpiry = ChronoUnit.DAYS.between(now, inv.getExpiryDate());
            dto.setDaysUntilExpiry(daysUntilExpiry);
            dto.setIsExpired(daysUntilExpiry < 0);
            dto.setExpiryDate(inv.getExpiryDate());
            boolean isExpiringSoon = false;
            if (expiringSoonDays != null) {
              isExpiringSoon = daysUntilExpiry >= 0 && daysUntilExpiry <= expiringSoonDays;
            } else {
              isExpiringSoon = daysUntilExpiry >= 0 && daysUntilExpiry <= 30;
            }
            dto.setIsExpiringSoon(isExpiringSoon);
          } else {
            dto.setDaysUntilExpiry(null);
            dto.setIsExpired(false);
            dto.setIsExpiringSoon(false);
          }
          dto.setExpiryDate(inv.getExpiryDate());

          BigDecimal turnoverRatio = calculateTurnoverRatio(received, sold, inv.getReceivedDate(), now);
          dto.setTurnoverRatio(turnoverRatio);

          Instant lastSoldDate = lastSoldDates.get(inv.getId());
          dto.setLastSoldDate(lastSoldDate);

          boolean isDeadStock = false;
          if (lastSoldDate != null) {
            long daysSinceLastSale = ChronoUnit.DAYS.between(lastSoldDate, now);
            if (deadStockDays != null) {
              isDeadStock = daysSinceLastSale >= deadStockDays && current > 0;
            } else {
              isDeadStock = daysSinceLastSale >= 90 && current > 0;
            }
          } else if (current > 0 && inv.getReceivedDate() != null) {
            long daysSinceReceived = ChronoUnit.DAYS.between(inv.getReceivedDate(), now);
            if (deadStockDays != null) {
              isDeadStock = daysSinceReceived >= deadStockDays;
            } else {
              isDeadStock = daysSinceReceived >= 90;
            }
          }
          dto.setIsDeadStock(isDeadStock);

          BigDecimal costPrice = inv.getCostPrice() != null ? inv.getCostPrice() : BigDecimal.ZERO;
          BigDecimal priceToRetail = inv.getSellingPrice() != null ? inv.getSellingPrice()
              : (inv.getPriceToRetail() != null ? inv.getPriceToRetail() : BigDecimal.ZERO);
          BigDecimal costValue = costPrice.multiply(BigDecimal.valueOf(current));
          BigDecimal retailValue = priceToRetail.multiply(BigDecimal.valueOf(current));
          BigDecimal potentialProfit = retailValue.subtract(costValue);
          dto.setCostValue(costValue);
          dto.setRetailValue(retailValue);
          dto.setPotentialProfit(potentialProfit);

          BigDecimal marginPercent = BigDecimal.ZERO;
          if (priceToRetail.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal margin = priceToRetail.subtract(costPrice);
            marginPercent = margin.divide(priceToRetail, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
          }
          dto.setMarginPercent(marginPercent);

          return dto;
        })
        .collect(Collectors.toList());
  }

  private Map<String, Instant> getLastSoldDates(String shopId, List<Inventory> inventories) {
    Map<String, Instant> lastSoldDates = new HashMap<>();
    List<Purchase> purchases = purchaseRepository.findByShopId(shopId, Pageable.unpaged())
        .getContent()
        .stream()
        .filter(p -> p.getStatus() == PurchaseStatus.COMPLETED)
        .filter(p -> p.getSoldAt() != null)
        .sorted((a, b) -> b.getSoldAt().compareTo(a.getSoldAt()))
        .collect(Collectors.toList());

    Set<String> processed = new HashSet<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() != null) {
        for (PurchaseItem item : purchase.getItems()) {
          String inventoryId = item.getInventoryId();
          if (!processed.contains(inventoryId)) {
            lastSoldDates.put(inventoryId, purchase.getSoldAt());
            processed.add(inventoryId);
          }
        }
      }
    }
    return lastSoldDates;
  }

  private BigDecimal calculateTurnoverRatio(Integer received, Integer sold, Instant receivedDate, Instant now) {
    if (received == null || received == 0 || sold == null || sold == 0) {
      return BigDecimal.ZERO;
    }
    Integer averageInventory = (received + (received - sold)) / 2;
    if (averageInventory == 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal months = BigDecimal.ONE;
    if (receivedDate != null) {
      long days = ChronoUnit.DAYS.between(receivedDate, now);
      if (days > 0) {
        months = BigDecimal.valueOf(days).divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
      }
    }
    BigDecimal turnover = BigDecimal.valueOf(sold)
        .divide(BigDecimal.valueOf(averageInventory), 4, RoundingMode.HALF_UP);
    if (months.compareTo(BigDecimal.ZERO) > 0) {
      turnover = turnover.divide(months, 4, RoundingMode.HALF_UP)
          .multiply(BigDecimal.valueOf(12));
    }
    return turnover;
  }

  private int getCurrentBaseCount(Inventory inventory) {
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() != null) {
      return inventory.getCurrentCount()
          .multiply(BigDecimal.valueOf(getDisplayToBaseFactor(inventory)))
          .setScale(0, RoundingMode.HALF_UP)
          .intValue();
    }
    return 0;
  }

  private int getSoldBaseCount(Inventory inventory) {
    if (inventory.getSoldBaseCount() != null) {
      return inventory.getSoldBaseCount();
    }
    if (inventory.getSoldCount() != null) {
      return inventory.getSoldCount()
          .multiply(BigDecimal.valueOf(getDisplayToBaseFactor(inventory)))
          .setScale(0, RoundingMode.HALF_UP)
          .intValue();
    }
    return 0;
  }

  private int getReceivedBaseCount(Inventory inventory) {
    if (inventory.getReceivedBaseCount() != null) {
      return inventory.getReceivedBaseCount();
    }
    if (inventory.getReceivedCount() != null) {
      return inventory.getReceivedCount()
          .multiply(BigDecimal.valueOf(getDisplayToBaseFactor(inventory)))
          .setScale(0, RoundingMode.HALF_UP)
          .intValue();
    }
    return 0;
  }

  private int getDisplayToBaseFactor(Inventory inventory) {
    if (inventory.getUnitConversions() == null
        || inventory.getUnitConversions().getFactor() == null
        || inventory.getUnitConversions().getFactor() <= 0) {
      return 1;
    }
    return inventory.getUnitConversions().getFactor();
  }

  public InventorySummaryDto calculateSummary(List<InventoryAnalyticsDto> analytics) {
    if (analytics.isEmpty()) {
      return new InventorySummaryDto(0, 0, 0, 0, 0,
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
          BigDecimal.ZERO, BigDecimal.ZERO);
    }

    int totalProducts = analytics.size();
    int lowStockProducts = (int) analytics.stream().filter(InventoryAnalyticsDto::getIsLowStock).count();
    int expiredProducts = (int) analytics.stream()
        .filter(dto -> dto.getIsExpired() != null && dto.getIsExpired())
        .count();
    int expiringSoonProducts = (int) analytics.stream()
        .filter(dto -> dto.getIsExpiringSoon() != null && dto.getIsExpiringSoon())
        .count();
    int deadStockProducts = (int) analytics.stream().filter(InventoryAnalyticsDto::getIsDeadStock).count();

    BigDecimal totalCostValue = analytics.stream()
        .map(InventoryAnalyticsDto::getCostValue)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalRetailValue = analytics.stream()
        .map(InventoryAnalyticsDto::getRetailValue)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal totalPotentialProfit = analytics.stream()
        .map(InventoryAnalyticsDto::getPotentialProfit)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal avgTurnoverRatio = analytics.stream()
        .map(InventoryAnalyticsDto::getTurnoverRatio)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(totalProducts), 2, RoundingMode.HALF_UP);

    BigDecimal avgStockPercentage = analytics.stream()
        .map(InventoryAnalyticsDto::getStockPercentage)
        .filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(totalProducts), 2, RoundingMode.HALF_UP);

    return new InventorySummaryDto(
        totalProducts,
        lowStockProducts,
        expiredProducts,
        expiringSoonProducts,
        deadStockProducts,
        totalCostValue,
        totalRetailValue,
        totalPotentialProfit,
        avgTurnoverRatio,
        avgStockPercentage
    );
  }
}
