package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.product.rest.dto.response.CustomerProductHistoryGroupDto;
import com.inventory.product.rest.dto.response.CustomerProductHistoryResponse;
import com.inventory.product.rest.dto.response.CustomerProductSaleEntryDto;
import com.inventory.product.util.PurchaseItemRefs;
import com.inventory.user.service.CustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
  private MongoTemplate mongoTemplate;

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
    List<Purchase> purchases = findRecentMatchingPurchases(
        shopId, resolvedCustomerId, excludePurchaseId, refBuckets);

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
        String matchedRef = matchSellableRef(item, refSet, refBuckets);
        if (matchedRef == null) {
          continue;
        }
        List<CustomerProductSaleEntryDto> entries = buckets.get(matchedRef);
        if (entries.size() >= limit) {
          continue;
        }
        entries.add(toEntry(purchase, item, soldAt));
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
      RefBuckets refBuckets) {

    List<Criteria> itemMatchers = new ArrayList<>();
    if (!refBuckets.sellableRefs().isEmpty()) {
      itemMatchers.add(Criteria.where("items.sellableRef").in(refBuckets.sellableRefs()));
    }
    if (!refBuckets.lotIds().isEmpty()) {
      itemMatchers.add(Criteria.where("items.inventoryId").in(refBuckets.lotIds()));
    }
    if (!refBuckets.menuItemIds().isEmpty()) {
      itemMatchers.add(Criteria.where("items.menuItemId").in(refBuckets.menuItemIds()));
    }

    Criteria base = Criteria.where("shopId").is(shopId)
        .and("customerId").is(customerId)
        .and("status").is(PurchaseStatus.COMPLETED);
    if (StringUtils.hasText(excludePurchaseId)) {
      base = base.and("_id").ne(excludePurchaseId.trim());
    }

    Criteria filter = base;
    if (!itemMatchers.isEmpty()) {
      filter = new Criteria().andOperator(
          base,
          new Criteria().orOperator(itemMatchers.toArray(new Criteria[0])));
    }

    Query query = new Query(filter)
        .with(Sort.by(Sort.Direction.DESC, "soldAt"))
        .limit(MAX_PURCHASES_SCAN);
    return mongoTemplate.find(query, Purchase.class);
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

  private static String matchSellableRef(PurchaseItem item, Set<String> refSet, RefBuckets refBuckets) {
    PurchaseItemRefs.normalize(item);
    String sellableRef = item.getSellableRef();
    if (StringUtils.hasText(sellableRef) && refSet.contains(sellableRef)) {
      return sellableRef;
    }
    String legacyLot = item.getMongoInventoryId();
    if (StringUtils.hasText(legacyLot)) {
      String encoded = SellableRef.inventory(legacyLot).encode();
      if (refSet.contains(encoded)) {
        return encoded;
      }
    }
    String legacyMenu = item.getMongoMenuItemId();
    if (StringUtils.hasText(legacyMenu)) {
      String encoded = SellableRef.menu(legacyMenu).encode();
      if (refSet.contains(encoded)) {
        return encoded;
      }
    }
    return null;
  }

  private static CustomerProductSaleEntryDto toEntry(Purchase purchase, PurchaseItem item, Instant soldAt) {
    BigDecimal quantity = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
    BigDecimal price = item.getPriceToRetail() != null ? item.getPriceToRetail() : BigDecimal.ZERO;
    BigDecimal lineTotal = item.getTotalAmount();
    if (lineTotal == null) {
      lineTotal = price.multiply(quantity);
    }
    return new CustomerProductSaleEntryDto(
        soldAt,
        purchase.getInvoiceNo(),
        purchase.getId(),
        quantity,
        price,
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
