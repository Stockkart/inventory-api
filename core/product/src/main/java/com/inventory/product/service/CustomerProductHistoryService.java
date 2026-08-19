package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.rest.dto.response.CustomerProductHistoryGroupDto;
import com.inventory.product.rest.dto.response.CustomerProductHistoryResponse;
import com.inventory.product.rest.dto.response.CustomerProductSaleEntryDto;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.domain.repository.PurchaseRepository;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.user.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(readOnly = true)
public class CustomerProductHistoryService {

  private static final int MAX_REFS = 50;
  private static final int DEFAULT_LIMIT_PER_REF = 3;
  private static final int MAX_LIMIT_PER_REF = 10;
  /** Cap scanned purchases so we never walk the full customer history. */
  private static final int MAX_PURCHASES_SCAN = 120;

  @Autowired
  private PurchaseRepository purchaseRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private CustomerService customerService;

  public CustomerProductHistoryResponse getHistory(
      String shopId,
      String customerId,
      String customerPhone,
      List<String> sellableRefs,
      Integer limitPerRef,
      String excludePurchaseId) {

    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }

    String resolvedCustomerId = resolveCustomerId(shopId, customerId, customerPhone);
    List<String> refs = normalizeSellableRefs(sellableRefs);
    if (refs.isEmpty()) {
      return new CustomerProductHistoryResponse(Map.of());
    }

    int limit = clampLimit(limitPerRef);
    RefBuckets refBuckets = splitRefs(refs);

    // What is being scanned, as products rather than as lots. A lot is one
    // delivery and is replaced every time stock is received, so it cannot answer
    // "has this customer bought this before" -- the question is about the
    // medicine, not the box it arrived in.
    Map<String, String> storedNamesByKey = new LinkedHashMap<>();
    Map<String, String> keyByRef = resolveProductKeys(shopId, refs, refBuckets,
        storedNamesByKey);
    Map<String, List<String>> refsByKey = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : keyByRef.entrySet()) {
      refsByKey.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
          .add(entry.getKey());
    }
    // Names for the query, as the sale line stores them. The key is lower-cased
    // for comparison, but items.name keeps the product's own casing and a Mongo
    // $in is case sensitive -- querying with the lower-cased form matches nothing
    // and the older batch is never fetched at all. Both forms are sent so a line
    // written either way is found.
    Set<String> targetNames = new LinkedHashSet<>();
    for (String key : keyByRef.values()) {
      targetNames.add(nameOfKey(key));
    }
    targetNames.addAll(storedNamesByKey.values());

    List<Purchase> purchases = findRecentMatchingPurchases(
        shopId, resolvedCustomerId, excludePurchaseId, refBuckets, targetNames);

    Map<String, List<CustomerProductSaleEntryDto>> buckets = initBuckets(refs);
    Set<String> refSet = new HashSet<>(refs);

    for (Purchase purchase : purchases) {
      if (allBucketsFull(buckets, limit)) {
        break;
      }
      Instant soldAt = resolveSoldAt(purchase);
      if (purchase.getItems() == null) {
        continue;
      }
      for (PurchaseItem item : purchase.getItems()) {
        for (String matchedRef : matchRefs(item, refSet, refsByKey)) {
          List<CustomerProductSaleEntryDto> entries = buckets.get(matchedRef);
          if (entries == null || entries.size() >= limit) {
            continue;
          }
          entries.add(toEntry(purchase, item, soldAt));
        }
      }
    }

    return buildResponse(buckets);
  }

  private String resolveCustomerId(String shopId, String customerId, String customerPhone) {
    if (StringUtils.hasText(customerId)) {
      return customerId.trim();
    }
    if (!StringUtils.hasText(customerPhone)) {
      throw new ValidationException("customerId or customerPhone is required");
    }
    return customerService.searchCustomerByPhone(customerPhone.trim(), shopId)
        .map(customer -> customer.getId())
        .orElseThrow(() -> new ValidationException("Customer not found for phone: " + customerPhone.trim()));
  }

  private List<String> normalizeSellableRefs(List<String> sellableRefs) {
    if (sellableRefs == null || sellableRefs.isEmpty()) {
      return List.of();
    }
    LinkedHashMap<String, String> unique = new LinkedHashMap<>();
    for (String raw : sellableRefs) {
      if (!StringUtils.hasText(raw)) {
        continue;
      }
      String trimmed = raw.trim();
      try {
        SellableRef.parse(trimmed);
      } catch (IllegalArgumentException ex) {
        log.debug("Skipping invalid sellableRef: {}", trimmed);
        continue;
      }
      unique.putIfAbsent(trimmed, trimmed);
      if (unique.size() >= MAX_REFS) {
        break;
      }
    }
    return new ArrayList<>(unique.keySet());
  }

  private int clampLimit(Integer limitPerRef) {
    if (limitPerRef == null || limitPerRef < 1) {
      return DEFAULT_LIMIT_PER_REF;
    }
    return Math.min(limitPerRef, MAX_LIMIT_PER_REF);
  }

  private List<Purchase> findRecentMatchingPurchases(
      String shopId,
      String customerId,
      String excludePurchaseId,
      RefBuckets refBuckets,
      Set<String> targetNames) {
    return purchaseRepository.findRecentForCustomerMatching(
        shopId,
        customerId,
        excludePurchaseId,
        refBuckets.sellableRefs(),
        refBuckets.menuItemIds(),
        targetNames,
        MAX_PURCHASES_SCAN);
  }

  private static Map<String, List<CustomerProductSaleEntryDto>> initBuckets(List<String> refs) {
    Map<String, List<CustomerProductSaleEntryDto>> buckets = new LinkedHashMap<>();
    for (String ref : refs) {
      buckets.put(ref, new ArrayList<>());
    }
    return buckets;
  }

  private static boolean allBucketsFull(Map<String, List<CustomerProductSaleEntryDto>> buckets, int limit) {
    for (List<CustomerProductSaleEntryDto> entries : buckets.values()) {
      if (entries.size() < limit) {
        return false;
      }
    }
    return true;
  }

  /**
   * A product identity as {@code normalizedName|companyName}.
   *
   * <p>Name and company, deliberately without baseUnit. The application's own
   * product identity includes the unit, but a legacy import can record the same
   * medicine under two units -- this shop has 142 names split across a {@code PCS}
   * and a {@code PH} product, {@code PH} being the old system's catch-all for
   * pieces. Those are one medicine to the person at the counter, and keying on
   * name and company joins them back together.
   */
  private static String productKey(String name, String companyName) {
    String normalizedName = name == null ? "" : name.trim().toLowerCase();
    String company = companyName == null ? "" : companyName.trim().toLowerCase();
    return normalizedName + "|" + company;
  }

  private static String nameOfKey(String key) {
    int bar = key.indexOf('|');
    return bar < 0 ? key : key.substring(0, bar);
  }

  /** The product identity behind each scanned ref. */
  private Map<String, String> resolveProductKeys(
      String shopId, List<String> refs, RefBuckets refBuckets,
      Map<String, String> storedNamesByKey) {
    Map<String, String> keyByRef = new LinkedHashMap<>();
    if (refBuckets.lotIds().isEmpty()) {
      return keyByRef;
    }
    Map<String, String> productIdByLot = new HashMap<>();
    for (Inventory lot : inventoryRepository.findByIdInAndShopId(
        refBuckets.lotIds(), shopId)) {
      if (StringUtils.hasText(lot.getProductId())) {
        productIdByLot.put(lot.getId(), lot.getProductId());
      }
    }
    Map<String, String> keyByProductId = loadProductKeys(
        new HashSet<>(productIdByLot.values()), storedNamesByKey);
    for (String ref : refs) {
      SellableRef parsed = SellableRef.parseLenient(ref);
      if (parsed == null || !SellableRef.KIND_INVENTORY.equals(parsed.kind())) {
        continue;
      }
      String key = keyByProductId.get(productIdByLot.get(parsed.id()));
      if (key != null) {
        keyByRef.put(ref, key);
      }
    }
    return keyByRef;
  }

  private Map<String, String> loadProductKeys(
      Set<String> productIds, Map<String, String> storedNamesByKey) {
    Map<String, String> out = new HashMap<>();
    if (productIds.isEmpty()) {
      return out;
    }
    for (Product product : productRepository.findAllById(productIds)) {
      String name = StringUtils.hasText(product.getNormalizedName())
          ? product.getNormalizedName() : product.getName();
      String key = productKey(name, product.getCompanyName());
      out.put(product.getId(), key);
      if (storedNamesByKey != null && StringUtils.hasText(product.getName())) {
        storedNamesByKey.put(key, product.getName());
      }
    }
    return out;
  }

  /**
   * Which of the scanned refs this sale line counts as history for.
   *
   * <p>Three ways in, narrowest first. An exact ref match is the same lot and
   * needs no lookup. Otherwise the line's own lot is resolved to its product and
   * compared on identity, which is what makes history survive a batch changing.
   * Failing that the line's recorded product name is compared, which is the only
   * route left for a sale whose lot no longer exists -- every migrated sale, and
   * anything old enough that its stock has since been cleared.
   */
  /**
   * Which of the scanned refs this sale line counts as history for.
   *
   * <p>The line's own lot is not consulted. A lot is one delivery, replaced
   * whenever stock is received, so comparing lots answers "was this the same
   * delivery" when the question is "was this the same medicine". The recorded
   * product name answers that directly, and keeps working for a sale whose lot
   * has since been consumed -- which is every migrated sale.
   *
   * <p>An exact ref match is kept ahead of it as a fast path, and because a cafe
   * menu ref has no product name to fall back to.
   */
  private List<String> matchRefs(
      PurchaseItem item,
      Set<String> refSet,
      Map<String, List<String>> refsByKey) {
    PurchaseItemRefs.normalize(item);

    String sellableRef = item.getSellableRef();
    if (StringUtils.hasText(sellableRef) && refSet.contains(sellableRef)) {
      return List.of(sellableRef);
    }

    String name = item.getName();
    if (StringUtils.hasText(name)) {
      String normalized = name.trim().toLowerCase();
      for (Map.Entry<String, List<String>> entry : refsByKey.entrySet()) {
        if (nameOfKey(entry.getKey()).equals(normalized)) {
          return entry.getValue();
        }
      }
    }
    return List.of();
  }

  private static CustomerProductSaleEntryDto toEntry(Purchase purchase, PurchaseItem item, Instant soldAt) {
    BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
    BigDecimal listPrice = item.getPriceToRetail() != null
        ? item.getPriceToRetail() : BigDecimal.ZERO;
    BigDecimal lineTotal = item.getTotalAmount();
    if (lineTotal == null) {
      lineTotal = listPrice.multiply(quantity);
    }

    // What was charged, not what was listed. priceToRetail is the PTR the line
    // started from; any discount or scheme applied at the counter lands in
    // totalAmount and never touches it. Reporting the PTR made the hint state a
    // price the customer was never charged -- two sales at 17 and 19 both read
    // as 20, the list price -- which is worse than showing nothing, because the
    // number looks authoritative while contradicting the invoice beside it.
    BigDecimal chargedPerUnit = listPrice;
    if (quantity.compareTo(BigDecimal.ZERO) > 0 && lineTotal.compareTo(BigDecimal.ZERO) > 0) {
      chargedPerUnit = lineTotal.divide(quantity, 2, RoundingMode.HALF_UP);
    }

    return new CustomerProductSaleEntryDto(
        soldAt,
        purchase.getInvoiceNo(),
        purchase.getId(),
        quantity,
        chargedPerUnit,
        lineTotal);
  }

  private static Instant resolveSoldAt(Purchase purchase) {
    if (purchase.getSoldAt() != null) {
      return purchase.getSoldAt();
    }
    if (purchase.getUpdatedAt() != null) {
      return purchase.getUpdatedAt();
    }
    return purchase.getCreatedAt();
  }

  private static CustomerProductHistoryResponse buildResponse(
      Map<String, List<CustomerProductSaleEntryDto>> buckets) {
    Map<String, CustomerProductHistoryGroupDto> bySellableRef = buckets.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> {
              List<CustomerProductSaleEntryDto> history = entry.getValue();
              CustomerProductSaleEntryDto lastSale = history.isEmpty() ? null : history.get(0);
              return new CustomerProductHistoryGroupDto(lastSale, history);
            },
            (a, b) -> a,
            LinkedHashMap::new));
    return new CustomerProductHistoryResponse(bySellableRef);
  }

  private static RefBuckets splitRefs(List<String> refs) {
    List<String> sellableRefs = new ArrayList<>();
    List<String> lotIds = new ArrayList<>();
    List<String> menuItemIds = new ArrayList<>();
    for (String ref : refs) {
      sellableRefs.add(ref);
      SellableRef parsed = SellableRef.parseLenient(ref);
      if (parsed == null) {
        continue;
      }
      if (parsed.isInventory()) {
        lotIds.add(parsed.id());
      } else if (parsed.isMenu()) {
        menuItemIds.add(parsed.id());
      }
    }
    return new RefBuckets(sellableRefs, lotIds, menuItemIds);
  }

  private record RefBuckets(List<String> sellableRefs, List<String> lotIds, List<String> menuItemIds) {}
}
