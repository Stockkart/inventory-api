package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.ref.SellableRef;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Purchase;
import com.inventory.product.domain.model.PurchaseItem;
import com.inventory.product.domain.model.enums.PurchaseStatus;
import com.inventory.user.domain.model.Customer;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private ProductRepository productRepository;

  public CustomerProductHistoryResponse getHistory(
      String shopId,
      String customerId,
      String customerPhone,
      String customerName,
      List<String> sellableRefs,
      Integer limitPerRef,
      String excludePurchaseId) {

    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("shopId is required");
    }

    String resolvedCustomerId =
        resolveCustomerId(shopId, customerId, customerPhone, customerName);
    List<String> refs = normalizeSellableRefs(sellableRefs);
    if (refs.isEmpty()) {
      return new CustomerProductHistoryResponse(Map.of());
    }

    int limit = clampLimit(limitPerRef);
    MatchIndex matchIndex = buildMatchIndex(shopId, refs);
    List<Purchase> purchases = findRecentMatchingPurchases(
        shopId, resolvedCustomerId, excludePurchaseId, matchIndex);
    resolveUnknownLots(purchases, matchIndex);

    Map<String, List<CustomerProductSaleEntryDto>> buckets = initBuckets(refs);

    for (Purchase purchase : purchases) {
      if (allBucketsFull(buckets, limit)) {
        break;
      }
      Instant soldAt = resolveSoldAt(purchase);
      if (purchase.getItems() == null) {
        continue;
      }
      for (PurchaseItem item : purchase.getItems()) {
        for (String matchedRef : matchIndex.matchRequestedRefs(item)) {
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

  /**
   * The customer whose history is wanted, identified by whichever of the three
   * the caller has.
   *
   * <p>The phone used to be the only way in, and most customers do not have one:
   * of one shop's thousand, six hundred have no phone recorded, and for every
   * one of them the lookup failed outright -- so the screen reported every line
   * as new to a customer who had been buying for three years.
   *
   * <p>The name is therefore accepted too. It identifies a customer less surely
   * than an id, so it is only used when it decides the matter on its own; where
   * several customers share a name the lookup fails rather than showing one
   * shop another's history.
   */
  private String resolveCustomerId(
      String shopId, String customerId, String customerPhone, String customerName) {
    if (StringUtils.hasText(customerId)) {
      return customerId.trim();
    }
    if (StringUtils.hasText(customerPhone)) {
      Optional<Customer> byPhone =
          customerService.searchCustomerByPhone(customerPhone.trim(), shopId);
      if (byPhone.isPresent()) {
        return byPhone.get().getId();
      }
      if (!StringUtils.hasText(customerName)) {
        throw new ValidationException("Customer not found for phone: " + customerPhone.trim());
      }
    }
    if (!StringUtils.hasText(customerName)) {
      throw new ValidationException("customerId, customerPhone or customerName is required");
    }
    return customerService.searchCustomerByName(customerName.trim(), shopId)
        .map(Customer::getId)
        .orElseThrow(() -> new ValidationException(
            "No single customer matches the name: " + customerName.trim()));
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

  private MatchIndex buildMatchIndex(String shopId, List<String> refs) {
    MatchIndex index = new MatchIndex(refs);
    List<String> inventoryIds = new ArrayList<>();
    for (String ref : refs) {
      SellableRef parsed = SellableRef.parseLenient(ref);
      if (parsed == null) {
        continue;
      }
      if (parsed.isMenu()) {
        index.addMenuRef(parsed.id(), ref);
      } else if (parsed.isInventory()) {
        inventoryIds.add(parsed.id());
        index.bindLotToRef(parsed.id(), ref);
      }
    }
    if (inventoryIds.isEmpty()) {
      return index;
    }

    Map<String, Inventory> byId = new HashMap<>();
    for (Inventory inv : inventoryRepository.findByIdIn(inventoryIds)) {
      if (inv != null && StringUtils.hasText(inv.getId())) {
        byId.put(inv.getId(), inv);
      }
    }

    Set<String> productIds = new LinkedHashSet<>();
    for (String requestedRef : refs) {
      SellableRef parsed = SellableRef.parseLenient(requestedRef);
      if (parsed == null || !parsed.isInventory()) {
        continue;
      }
      Inventory inv = byId.get(parsed.id());
      String name = inv != null ? inv.getName() : null;
      String company = inv != null ? inv.getCompanyName() : null;
      String productId = inv != null ? inv.getProductId() : null;
      CatalogIdentity identity = CatalogIdentity.from(productId, name, company);
      index.bindIdentity(requestedRef, identity);
      if (StringUtils.hasText(productId)) {
        productIds.add(productId.trim());
      }
    }

    expandProductIdsByNameAndCompany(shopId, index, productIds);

    if (!productIds.isEmpty()) {
      for (Inventory sibling : inventoryRepository.findByShopIdAndProductIdIn(shopId, productIds)) {
        if (sibling == null || !StringUtils.hasText(sibling.getId())) {
          continue;
        }
        index.bindSiblingLot(sibling);
      }
    }
    return index;
  }

  /**
   * Historical lines may point at lots that were not in the cart. If that lot still exists and
   * belongs to a different company/name, skip name-only matching for it.
   */
  private void resolveUnknownLots(List<Purchase> purchases, MatchIndex matchIndex) {
    Set<String> unknownLotIds = new LinkedHashSet<>();
    for (Purchase purchase : purchases) {
      if (purchase.getItems() == null) {
        continue;
      }
      for (PurchaseItem item : purchase.getItems()) {
        PurchaseItemRefs.normalize(item);
        String lotId = PurchaseItemRefs.stockLotId(item);
        if (StringUtils.hasText(lotId) && !matchIndex.knowsLot(lotId)) {
          unknownLotIds.add(lotId);
        }
      }
    }
    if (unknownLotIds.isEmpty()) {
      return;
    }
    for (Inventory inv : inventoryRepository.findByIdIn(new ArrayList<>(unknownLotIds))) {
      if (inv == null || !StringUtils.hasText(inv.getId())) {
        continue;
      }
      matchIndex.observeHistoricalLot(inv);
    }
  }

  /**
   * Same catalog product can be registered under more than one productId after identity forks;
   * name + company is the shop-facing identity.
   */
  private void expandProductIdsByNameAndCompany(
      String shopId, MatchIndex index, Set<String> productIds) {
    Set<String> names = index.uniqueNormalizedNames();
    if (names.isEmpty()) {
      return;
    }
    for (String normalizedName : names) {
      List<Product> candidates = productRepository.findByShopIdAndNormalizedName(shopId, normalizedName);
      for (Product product : candidates) {
        if (product == null || !StringUtils.hasText(product.getId())) {
          continue;
        }
        CatalogIdentity catalog = CatalogIdentity.from(
            product.getId(), product.getName(), product.getCompanyName());
        if (index.matchesAnyIdentity(catalog)) {
          productIds.add(product.getId());
        }
      }
    }
  }

  private List<Purchase> findRecentMatchingPurchases(
      String shopId,
      String customerId,
      String excludePurchaseId,
      MatchIndex matchIndex) {

    List<Criteria> itemMatchers = new ArrayList<>();
    if (!matchIndex.sellableRefsToQuery().isEmpty()) {
      itemMatchers.add(Criteria.where("items.sellableRef").in(matchIndex.sellableRefsToQuery()));
    }
    if (!matchIndex.lotIdsToQuery().isEmpty()) {
      itemMatchers.add(Criteria.where("items.inventoryId").in(matchIndex.lotIdsToQuery()));
    }
    if (!matchIndex.menuItemIds().isEmpty()) {
      itemMatchers.add(Criteria.where("items.menuItemId").in(matchIndex.menuItemIds()));
    }
    for (String name : matchIndex.displayNames()) {
      itemMatchers.add(Criteria.where("items.name").regex("^" + Pattern.quote(name) + "$", "i"));
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
        lineTotal,
        item.getSaleAdditionalDiscount(),
        item.getSchemeType(),
        item.getSchemePayFor(),
        item.getSchemeFree(),
        item.getSchemePercentage());
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

  record CatalogIdentity(String productId, String normalizedName, String normalizedCompany, String displayName) {
    static CatalogIdentity from(String productId, String name, String companyName) {
      String display = StringUtils.hasText(name) ? name.trim() : null;
      return new CatalogIdentity(
          StringUtils.hasText(productId) ? productId.trim() : null,
          normalizeKey(name),
          normalizeKey(companyName),
          display);
    }

    boolean sameProduct(CatalogIdentity other) {
      if (other == null) {
        return false;
      }
      if (StringUtils.hasText(productId) && productId.equals(other.productId)) {
        return true;
      }
      return StringUtils.hasText(normalizedName)
          && normalizedName.equals(other.normalizedName)
          && normalizedCompany.equals(other.normalizedCompany);
    }

    boolean nameEquals(String rawName) {
      return StringUtils.hasText(normalizedName) && normalizedName.equals(normalizeKey(rawName));
    }
  }

  static String normalizeKey(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Maps a historical purchase line back onto the current cart sellableRefs.
   * Inventory lines match the current lot, sibling lots of the same catalog
   * product, or the same product name + company (when the old lot is gone).
   */
  static final class MatchIndex {
    private final Set<String> requestedRefs;
    private final Map<String, String> menuIdToRef = new HashMap<>();
    private final Map<String, Set<String>> lotIdToRefs = new HashMap<>();
    private final Map<String, CatalogIdentity> refToIdentity = new LinkedHashMap<>();
    private final Set<String> menuItemIds = new LinkedHashSet<>();
    private final Set<String> sellableRefsToQuery = new LinkedHashSet<>();
    private final Set<String> lotIdsToQuery = new LinkedHashSet<>();
    private final Set<String> displayNames = new LinkedHashSet<>();
    private final Set<String> conflictingLots = new HashSet<>();

    MatchIndex(List<String> requestedRefs) {
      this.requestedRefs = new LinkedHashSet<>(requestedRefs);
      this.sellableRefsToQuery.addAll(requestedRefs);
    }

    void addMenuRef(String menuItemId, String requestedRef) {
      menuIdToRef.put(menuItemId, requestedRef);
      menuItemIds.add(menuItemId);
    }

    void bindLotToRef(String lotId, String requestedRef) {
      lotIdToRefs.computeIfAbsent(lotId, key -> new LinkedHashSet<>()).add(requestedRef);
      lotIdsToQuery.add(lotId);
    }

    void bindIdentity(String requestedRef, CatalogIdentity identity) {
      refToIdentity.put(requestedRef, identity);
      if (identity.displayName() != null) {
        displayNames.add(identity.displayName());
      }
    }

    void bindSiblingLot(Inventory sibling) {
      CatalogIdentity siblingIdentity = CatalogIdentity.from(
          sibling.getProductId(), sibling.getName(), sibling.getCompanyName());
      for (Map.Entry<String, CatalogIdentity> entry : refToIdentity.entrySet()) {
        if (entry.getValue().sameProduct(siblingIdentity)) {
          bindLotToRef(sibling.getId(), entry.getKey());
          sellableRefsToQuery.add(SellableRef.inventory(sibling.getId()).encode());
        }
      }
    }

    boolean matchesAnyIdentity(CatalogIdentity candidate) {
      for (CatalogIdentity identity : refToIdentity.values()) {
        if (identity.sameProduct(candidate)) {
          return true;
        }
      }
      return false;
    }

    Set<String> uniqueNormalizedNames() {
      Set<String> names = new LinkedHashSet<>();
      for (CatalogIdentity identity : refToIdentity.values()) {
        if (StringUtils.hasText(identity.normalizedName())) {
          names.add(identity.normalizedName());
        }
      }
      return names;
    }

    Set<String> sellableRefsToQuery() {
      return sellableRefsToQuery;
    }

    boolean knowsLot(String lotId) {
      return lotIdToRefs.containsKey(lotId) || conflictingLots.contains(lotId);
    }

    void observeHistoricalLot(Inventory inv) {
      CatalogIdentity observed = CatalogIdentity.from(
          inv.getProductId(), inv.getName(), inv.getCompanyName());
      if (matchesAnyIdentity(observed)) {
        bindSiblingLot(inv);
        return;
      }
      conflictingLots.add(inv.getId());
    }

    Set<String> lotIdsToQuery() {
      return lotIdsToQuery;
    }

    Set<String> menuItemIds() {
      return menuItemIds;
    }

    Set<String> displayNames() {
      return displayNames;
    }

    Set<String> matchRequestedRefs(PurchaseItem item) {
      PurchaseItemRefs.normalize(item);
      Set<String> matched = new LinkedHashSet<>();

      String sellableRef = item.getSellableRef();
      if (StringUtils.hasText(sellableRef) && requestedRefs.contains(sellableRef)) {
        matched.add(sellableRef);
      }

      String lotId = PurchaseItemRefs.stockLotId(item);
      if (StringUtils.hasText(lotId)) {
        Set<String> byLot = lotIdToRefs.get(lotId);
        if (byLot != null) {
          matched.addAll(byLot);
        }
      }

      String legacyMenu = item.getMongoMenuItemId();
      if (StringUtils.hasText(legacyMenu) && menuIdToRef.containsKey(legacyMenu)) {
        matched.add(menuIdToRef.get(legacyMenu));
      }
      SellableRef parsed = SellableRef.parseLenient(sellableRef);
      if (parsed != null && parsed.isMenu() && menuIdToRef.containsKey(parsed.id())) {
        matched.add(menuIdToRef.get(parsed.id()));
      }

      matched.addAll(matchByNameAndCompany(item));
      return matched;
    }

    private Set<String> matchByNameAndCompany(PurchaseItem item) {
      String lotId = PurchaseItemRefs.stockLotId(item);
      if (StringUtils.hasText(lotId) && conflictingLots.contains(lotId)) {
        return Set.of();
      }
      if (!StringUtils.hasText(item.getName())) {
        return Set.of();
      }
      List<Map.Entry<String, CatalogIdentity>> nameHits = new ArrayList<>();
      for (Map.Entry<String, CatalogIdentity> entry : refToIdentity.entrySet()) {
        if (entry.getValue().nameEquals(item.getName())) {
          nameHits.add(entry);
        }
      }
      if (nameHits.isEmpty()) {
        return Set.of();
      }
      Set<String> companies = new HashSet<>();
      for (Map.Entry<String, CatalogIdentity> hit : nameHits) {
        companies.add(hit.getValue().normalizedCompany());
      }
      // Ambiguous when the cart has the same name from more than one company.
      if (companies.size() > 1) {
        return Set.of();
      }
      Set<String> refs = new LinkedHashSet<>();
      for (Map.Entry<String, CatalogIdentity> hit : nameHits) {
        refs.add(hit.getKey());
      }
      return refs;
    }
  }
}
