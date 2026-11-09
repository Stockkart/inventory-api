package com.inventory.analytics.utils;

import com.inventory.pluginengine.VerticalFieldsReader;
import com.inventory.product.domain.model.Inventory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Vendors tab's per-vendor stock figures, gathered one lot at a time (the tab used to hold
 * every lot of the shop to compute them). Same arithmetic as before; vendors and vendor/category
 * pairs are remembered in the order their first lot came, so the tab's maps are built in the same
 * order and ties sort as they did. Lots the period sold from are kept for the revenue figures.
 */
public final class VendorLotTotals {

  static final class Stock {
    int received;
    int sold;
    int unsold;
    int expired;
    BigDecimal unsoldValue = BigDecimal.ZERO;
    BigDecimal expiredValue = BigDecimal.ZERO;
    int products;
    final Set<String> lotIds = new HashSet<>();
  }

  static final class Performance {
    long daysInStock;
    int itemsForDays;
    int fastMoving;
    int received;
    int expiredItems;
    int deadStockItems;
    BigDecimal deadStockValue = BigDecimal.ZERO;
    BigDecimal expiredValue = BigDecimal.ZERO;
  }

  static final class Category {
    final String vendorId;
    final String businessType;
    int received;
    int expired;
    BigDecimal expiredValue = BigDecimal.ZERO;

    Category(String vendorId, String businessType) {
      this.vendorId = vendorId;
      this.businessType = businessType;
    }
  }

  private final Set<String> soldLotIds;
  private final Map<String, Inventory> soldLots = new HashMap<>();
  private final Instant now;

  /** Vendor ids in the order their first lot came. */
  final LinkedHashSet<String> vendorIds = new LinkedHashSet<>();
  final Map<String, Stock> stock = new HashMap<>();
  final Map<String, Performance> performance = new HashMap<>();
  /** {@code vendorId|businessType} in the order their first lot came. */
  final LinkedHashMap<String, Category> categories = new LinkedHashMap<>();
  /** Stock value (cost × current) per vendor, and over all lots. */
  final Map<String, BigDecimal> valueByVendor = new HashMap<>();
  BigDecimal totalValue = BigDecimal.ZERO;
  BigDecimal expiredValue = BigDecimal.ZERO;
  int lots;

  /** {@code soldLotIds}: lots the period's lines sold from (kept for revenue). */
  public VendorLotTotals(Collection<String> soldLotIds, Instant now) {
    this.soldLotIds = new HashSet<>(soldLotIds);
    this.now = now;
  }

  /** One lot, with its vertical extension fields (for expiry). */
  public void add(Inventory inv, Map<String, Object> extension) {
    lots++;
    if (soldLotIds.contains(inv.getId())) {
      soldLots.put(inv.getId(), inv);
    }
    int received = LotCounts.received(inv);
    int sold = LotCounts.sold(inv);
    int current = LotCounts.current(inv);
    BigDecimal cost = inv.costForValuation() != null ? inv.costForValuation() : BigDecimal.ZERO;
    BigDecimal value = cost.multiply(BigDecimal.valueOf(current));
    Instant expiry = VerticalFieldsReader.expiryDateFrom(extension);
    boolean isExpired = expiry != null && expiry.isBefore(now);

    totalValue = totalValue.add(value);
    if (isExpired) {
      expiredValue = expiredValue.add(value);
    }

    String vendorId = inv.getVendorId();
    if (vendorId == null) {
      return;
    }
    vendorIds.add(vendorId);
    valueByVendor.merge(vendorId, value, BigDecimal::add);

    Stock s = stock.computeIfAbsent(vendorId, k -> new Stock());
    s.received += received;
    s.sold += sold;
    s.unsold += current;
    if (isExpired) {
      s.expired += current;
      s.expiredValue = s.expiredValue.add(value);
    }
    s.unsoldValue = s.unsoldValue.add(value);
    s.products++;
    if (inv.getLotId() != null) {
      s.lotIds.add(inv.getLotId());
    }

    Performance p = performance.computeIfAbsent(vendorId, k -> new Performance());
    if (inv.getReceivedDate() != null) {
      long days = ChronoUnit.DAYS.between(inv.getReceivedDate(), now);
      p.daysInStock += days * received;
      p.itemsForDays += received;
      if (days > 90 && current > 0) {
        p.deadStockItems += current;
        p.deadStockValue = p.deadStockValue.add(value);
      }
      if (sold > 0 && days <= 30) {
        p.fastMoving += sold;
      }
    }
    if (isExpired) {
      p.expiredItems += current;
      p.expiredValue = p.expiredValue.add(value);
    }
    p.received += received;

    if (inv.getBusinessType() != null) {
      Category c = categories.computeIfAbsent(
          vendorId + "|" + inv.getBusinessType(), k -> new Category(vendorId, inv.getBusinessType()));
      c.received += received;
      if (isExpired) {
        c.expired += current;
        c.expiredValue = c.expiredValue.add(value);
      }
    }
  }

  /** The kept lots the period sold from, by id. */
  public Map<String, Inventory> soldLots() {
    return soldLots;
  }

  /** Vendor ids of the shop's lots, in first-lot order. */
  public Set<String> vendorIds() {
    return vendorIds;
  }

  public int lots() {
    return lots;
  }

  /** Cost × current over all lots. */
  public BigDecimal totalValue() {
    return totalValue;
  }

  /** Cost × current over expired lots. */
  public BigDecimal expiredValue() {
    return expiredValue;
  }

  /** Base counts, display counts scaled by the product's unit factor when the base is missing. */
  static final class LotCounts {
    private LotCounts() {}

    static int current(Inventory inv) {
      return base(inv.getCurrentBaseCount(), inv.getCurrentCount(), inv);
    }

    static int sold(Inventory inv) {
      return base(inv.getSoldBaseCount(), inv.getSoldCount(), inv);
    }

    static int received(Inventory inv) {
      return base(inv.getReceivedBaseCount(), inv.getReceivedCount(), inv);
    }

    private static int base(Integer stored, BigDecimal display, Inventory inv) {
      if (stored != null) {
        return stored;
      }
      if (display != null) {
        return display.multiply(BigDecimal.valueOf(factor(inv))).setScale(0, RoundingMode.HALF_UP).intValue();
      }
      return 0;
    }

    private static int factor(Inventory inv) {
      if (inv.getUnitConversions() == null
          || inv.getUnitConversions().getFactor() == null
          || inv.getUnitConversions().getFactor() <= 0) {
        return 1;
      }
      return inv.getUnitConversions().getFactor();
    }
  }
}
