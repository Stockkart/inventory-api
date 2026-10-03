package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.BarcodePool;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import com.inventory.product.domain.repository.BarcodePoolRepository;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.labels.EffectiveLayout;
import com.inventory.product.labels.FieldCatalog;
import com.inventory.product.labels.LabelDataResolver;
import com.inventory.product.labels.LabelDataResolver.LabelTarget;
import com.inventory.product.labels.LabelDataResolver.ResolutionContext;
import com.inventory.product.labels.LabelExtensionReader;
import com.inventory.product.labels.LabelFieldCatalogService;
import com.inventory.product.labels.LabelLayoutService;
import com.inventory.product.labels.LotSelector;
import com.inventory.product.rest.dto.request.AttachBarcodeRequest;
import com.inventory.product.rest.dto.request.BarcodeLabelsRequest;
import com.inventory.product.rest.dto.request.GenerateBarcodesRequest;
import com.inventory.product.rest.dto.response.BarcodeLabelsResponse;
import com.inventory.product.rest.dto.response.BarcodePoolListResponse;
import com.inventory.product.rest.dto.response.GenerateBarcodesResponse;
import com.inventory.product.rest.dto.response.LabelLayoutResponse;
import com.inventory.product.validation.ProductValidator;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@Transactional
public class BarcodeService {

  private static final int MAX_GENERATE = 500;
  private static final int DEFAULT_LIST_LIMIT = 100;
  private static final int MAX_LIST_LIMIT = 500;
  private static final int MAX_LABEL_INPUTS = 500;

  /** Where a labels row came from; drives {@link LabelTarget} construction (Req 6.8, 6.9). */
  private record LabelSource(String code, Product product, BarcodePool poolRow) {}

  @Autowired
  private BarcodeGeneratorService barcodeGeneratorService;

  @Autowired
  private BarcodePoolRepository barcodePoolRepository;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private InventoryRepository inventoryRepository;

  @Autowired
  private ProductService productService;

  @Autowired
  private ProductValidator productValidator;

  @Autowired
  private MetricsWrapper metrics;

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private PricingRepository pricingRepository;

  @Autowired
  private LabelFieldCatalogService catalogService;

  @Autowired
  private LabelLayoutService layoutService;

  @Autowired
  private LabelExtensionReader extensionReader;

  @Autowired
  private LabelDataResolver resolver;

  /**
   * Generate unique codes and store them as UNUSED pool rows (also used for count=1 at registration).
   */
  public GenerateBarcodesResponse generate(GenerateBarcodesRequest request, String shopId) {
    int count = request != null && request.getCount() != null ? request.getCount() : 1;
    if (count < 1 || count > MAX_GENERATE) {
      throw new ValidationException("count must be between 1 and " + MAX_GENERATE);
    }
    String batchId =
        request != null && StringUtils.hasText(request.getBatchId())
            ? request.getBatchId().trim()
            : null;

    Instant now = Instant.now();
    List<GenerateBarcodesResponse.BarcodePoolItemDto> items = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      String code = barcodeGeneratorService.generateUnique(shopId);
      BarcodePool pool = new BarcodePool();
      pool.setShopId(shopId);
      pool.setCode(code);
      pool.setStatus(BarcodePoolStatus.UNUSED);
      pool.setBatchId(batchId);
      pool.setCreatedAt(now);
      pool.setUpdatedAt(now);
      pool = barcodePoolRepository.save(pool);
      items.add(toPoolDto(pool));
    }
    log.info("Generated {} barcodes for shop {}", count, shopId);
    metrics.record(
        ProductMetricsConstants.BARCODES_GENERATED,
        count,
        "module",
        ProductMetricsConstants.MODULE);
    return new GenerateBarcodesResponse(items);
  }

  @Transactional(readOnly = true)
  public BarcodePoolListResponse list(String shopId, String status, String q, Integer limit) {
    int pageSize = limit != null ? Math.min(Math.max(limit, 1), MAX_LIST_LIMIT) : DEFAULT_LIST_LIMIT;
    PageRequest page = PageRequest.of(0, pageSize);
    List<BarcodePool> rows;
    if (StringUtils.hasText(q)) {
      rows = barcodePoolRepository.searchByShopIdAndQuery(shopId, q.trim(), page);
    } else if (StringUtils.hasText(status)) {
      BarcodePoolStatus st;
      try {
        st = BarcodePoolStatus.valueOf(status.trim().toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ValidationException("status must be UNUSED or ATTACHED");
      }
      rows = barcodePoolRepository.findByShopIdAndStatus(shopId, st, page);
    } else {
      rows = barcodePoolRepository.findByShopId(shopId, page);
    }
    return new BarcodePoolListResponse(rows.stream().map(BarcodeService::toPoolDto).toList());
  }

  /**
   * Attach an UNUSED pool code (or a free generated code) to a product in place.
   * Does not fork the product.
   */
  public GenerateBarcodesResponse.BarcodePoolItemDto attach(
      String code, AttachBarcodeRequest request, String shopId) {
    if (!StringUtils.hasText(code)) {
      throw new ValidationException("Barcode code is required");
    }
    if (request == null || !StringUtils.hasText(request.getProductId())) {
      throw new ValidationException("productId is required");
    }
    String normalized = productValidator.normalizeBarcode(code);
    productValidator.validateBarcode(normalized);

    Product product = productRepository
        .findByIdAndShopId(request.getProductId().trim(), shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Product", "id", request.getProductId()));

    BarcodePool pool = barcodePoolRepository
        .findByShopIdAndCode(shopId, normalized)
        .orElse(null);

    productService.updateBarcodeInPlace(product.getId(), shopId, normalized);

    Instant now = Instant.now();
    if (pool == null) {
      pool = new BarcodePool();
      pool.setShopId(shopId);
      pool.setCode(normalized);
      pool.setCreatedAt(now);
    }
    pool.setStatus(BarcodePoolStatus.ATTACHED);
    // Codes may be shared; the first attached product stays the pool's label source.
    if (!StringUtils.hasText(pool.getProductId())) {
      pool.setProductId(product.getId());
    }
    if (!StringUtils.hasText(pool.getLabelName())) {
      pool.setLabelName(product.getName());
    }
    if (!StringUtils.hasText(pool.getLabelCompany())) {
      pool.setLabelCompany(product.getCompanyName());
    }
    pool.setUpdatedAt(now);
    pool = barcodePoolRepository.save(pool);
    return toPoolDto(pool);
  }

  /**
   * When a product is saved with a barcode that exists in the pool, mark it ATTACHED. Codes may
   * be shared by several products; the first attached product stays the label source.
   */
  public void claimPoolForProduct(String shopId, String productId, String barcode) {
    String normalized = productValidator.normalizeBarcode(barcode);
    if (normalized == null || !StringUtils.hasText(productId)) {
      return;
    }
    Optional<BarcodePool> existing = barcodePoolRepository.findByShopIdAndCode(shopId, normalized);
    if (existing.isEmpty()) {
      return;
    }
    BarcodePool pool = existing.get();
    if (pool.getStatus() == BarcodePoolStatus.ATTACHED && StringUtils.hasText(pool.getProductId())) {
      return;
    }
    pool.setStatus(BarcodePoolStatus.ATTACHED);
    pool.setProductId(productId);
    pool.setUpdatedAt(Instant.now());
    barcodePoolRepository.save(pool);
  }

  @Transactional(readOnly = true)
  public BarcodeLabelsResponse labels(BarcodeLabelsRequest request, String shopId) {
    if (request == null) {
      throw new ValidationException("Request is required");
    }
    int inputCount =
        (request.getProductIds() == null ? 0 : request.getProductIds().size())
            + (request.getCodes() == null ? 0 : request.getCodes().size());
    if (inputCount > MAX_LABEL_INPUTS) {
      throw new ValidationException(
          "At most " + MAX_LABEL_INPUTS + " productIds and codes per request");
    }
    // Keyed by code + product: a shared code prints one label per product.
    Map<String, BarcodeLabelsResponse.BarcodeLabelDto> byCode = new LinkedHashMap<>();
    // Same keys as byCode; remembers the product / pool row each DTO was built from.
    Map<String, LabelSource> sources = new HashMap<>();
    java.util.Set<String> codesWithProduct = new java.util.HashSet<>();

    if (request.getProductIds() != null) {
      for (String productId : request.getProductIds()) {
        if (!StringUtils.hasText(productId)) {
          continue;
        }
        Product product = productRepository
            .findByIdAndShopId(productId.trim(), shopId)
            .orElse(null);
        if (product == null || !StringUtils.hasText(product.getBarcode())) {
          continue;
        }
        putProductLabel(byCode, sources, product.getBarcode(), product, shopId);
        codesWithProduct.add(product.getBarcode());
      }
    }

    if (request.getCodes() != null) {
      for (String code : request.getCodes()) {
        String normalized = productValidator.normalizeBarcode(code);
        if (normalized == null) {
          continue;
        }
        if (codesWithProduct.contains(normalized)) {
          continue;
        }
        List<Product> products = productRepository.findAllByShopIdAndBarcode(shopId, normalized);
        if (!products.isEmpty()) {
          for (Product product : products) {
            putProductLabel(byCode, sources, normalized, product, shopId);
          }
          codesWithProduct.add(normalized);
          continue;
        }
        BarcodePool pool = barcodePoolRepository.findByShopIdAndCode(shopId, normalized).orElse(null);
        if (pool != null) {
          byCode.put(
              normalized,
              new BarcodeLabelsResponse.BarcodeLabelDto(
                  normalized,
                  pool.getLabelName(),
                  pool.getLabelCompany(),
                  pool.getLabelPrice(),
                  pool.getProductId()));
          sources.put(normalized, new LabelSource(normalized, null, pool));
        } else {
          byCode.put(
              normalized,
              new BarcodeLabelsResponse.BarcodeLabelDto(normalized, null, null, null, null));
          sources.put(normalized, new LabelSource(normalized, null, null));
        }
      }
    }

    LabelLayoutResponse layout = resolveLabelValues(byCode, sources, request.getInventoryIds(), shopId);
    return new BarcodeLabelsResponse(new ArrayList<>(byCode.values()), layout);
  }

  /**
   * Fills {@code values} on every DTO against the shop's effective layout (Req 6.1, 6.11, 6.12)
   * and returns that layout. All documents are batch-loaded once per request; the lot per product
   * is {@link LotSelector}'s pick unless the request pins one via {@code inventoryIds} (Req 6.5),
   * which is validated before any value is produced (Req 6.14).
   */
  private LabelLayoutResponse resolveLabelValues(
      Map<String, BarcodeLabelsResponse.BarcodeLabelDto> byCode,
      Map<String, LabelSource> sources,
      Map<String, String> requestedInventoryIds,
      String shopId) {
    Shop shop =
        shopRepository
            .findById(shopId)
            .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
    FieldCatalog catalog = catalogService.catalog(shop);
    LabelLayoutResponse layout = layoutService.responseFor(shopId, catalog);
    EffectiveLayout effective = layout.toEffectiveLayout();
    ResolutionContext ctx = new ResolutionContext(shop, catalog, effective);

    // Lots for every product that made it into the response, in one query.
    Set<String> productIds = new LinkedHashSet<>();
    for (LabelSource source : sources.values()) {
      if (source.product() != null) {
        productIds.add(source.product().getId());
      }
    }
    List<Inventory> lots =
        productIds.isEmpty()
            ? List.of()
            : inventoryRepository.findByShopIdAndProductIdIn(shopId, productIds);
    Map<String, Inventory> autoLot = LotSelector.selectPerProduct(lots);
    Map<String, Inventory> lotById = new HashMap<>();
    for (Inventory lot : lots) {
      if (lot != null && lot.getId() != null) {
        lotById.put(lot.getId(), lot);
      }
    }

    // Explicit lots (code → inventoryId); fail fast on anything that does not line up.
    Map<String, Inventory> explicitLot = new HashMap<>();
    if (requestedInventoryIds != null) {
      for (Map.Entry<String, String> entry : requestedInventoryIds.entrySet()) {
        String code = productValidator.normalizeBarcode(entry.getKey());
        String inventoryId = entry.getValue();
        if (code == null || !StringUtils.hasText(inventoryId)) {
          continue;
        }
        Inventory lot = lotById.get(inventoryId.trim());
        if (lot == null) {
          lot =
              inventoryRepository
                  .findById(inventoryId.trim())
                  .filter(l -> shopId.equals(l.getShopId()))
                  .orElse(null);
        }
        if (lot == null || !codeHasProduct(sources, code, lot.getProductId())) {
          throw new ValidationException(
              "inventoryId "
                  + inventoryId
                  + " does not exist or does not belong to the product for code "
                  + code);
        }
        explicitLot.put(code, lot);
      }
    }

    // Chosen lot per response row, then pricing docs and extension rows for those lots.
    Map<String, Inventory> chosenLot = new HashMap<>();
    Set<String> pricingIds = new LinkedHashSet<>();
    Set<String> lotIds = new LinkedHashSet<>();
    for (Map.Entry<String, LabelSource> entry : sources.entrySet()) {
      LabelSource source = entry.getValue();
      if (source.product() == null) {
        continue;
      }
      Inventory explicit = explicitLot.get(source.code());
      Inventory lot =
          explicit != null && source.product().getId().equals(explicit.getProductId())
              ? explicit
              : autoLot.get(source.product().getId());
      if (lot == null) {
        continue;
      }
      chosenLot.put(entry.getKey(), lot);
      if (StringUtils.hasText(lot.getPricingId())) {
        pricingIds.add(lot.getPricingId());
      }
      if (StringUtils.hasText(lot.getId())) {
        lotIds.add(lot.getId());
      }
    }
    Map<String, Pricing> pricingById = new HashMap<>();
    if (!pricingIds.isEmpty()) {
      for (Pricing pricing : pricingRepository.findAllById(pricingIds)) {
        if (pricing != null && pricing.getId() != null) {
          pricingById.put(pricing.getId(), pricing);
        }
      }
    }
    Map<String, Map<String, Object>> extRows = extensionReader.readByInventoryIds(shop, lotIds);

    for (Map.Entry<String, BarcodeLabelsResponse.BarcodeLabelDto> entry : byCode.entrySet()) {
      LabelSource source = sources.get(entry.getKey());
      Inventory lot = chosenLot.get(entry.getKey());
      Pricing pricing =
          lot == null || lot.getPricingId() == null ? null : pricingById.get(lot.getPricingId());
      Map<String, Object> extensionRow = lot == null ? null : extRows.get(lot.getId());
      LabelTarget target =
          new LabelTarget(
              entry.getValue().getCode(),
              source == null ? null : source.product(),
              lot,
              pricing,
              extensionRow,
              source == null ? null : source.poolRow());
      entry.getValue().setValues(resolver.resolve(target, ctx));
    }
    return layout;
  }

  /** True when some response row for {@code code} is backed by product {@code productId}. */
  private static boolean codeHasProduct(
      Map<String, LabelSource> sources, String code, String productId) {
    if (productId == null) {
      return false;
    }
    for (LabelSource source : sources.values()) {
      if (code.equals(source.code())
          && source.product() != null
          && productId.equals(source.product().getId())) {
        return true;
      }
    }
    return false;
  }

  private void putProductLabel(
      Map<String, BarcodeLabelsResponse.BarcodeLabelDto> labels,
      Map<String, LabelSource> sources,
      String code,
      Product product,
      String shopId) {
    String key = code + "\u0001" + product.getId();
    labels.put(
        key,
        new BarcodeLabelsResponse.BarcodeLabelDto(
            code,
            product.getName(),
            product.getCompanyName(),
            resolvePrice(shopId, product.getId(), null),
            product.getId()));
    sources.put(key, new LabelSource(code, product, null));
  }

  private BigDecimal resolvePrice(String shopId, String productId, BigDecimal fallback) {
    if (!StringUtils.hasText(productId)) {
      return fallback;
    }
    Optional<Inventory> lot =
        inventoryRepository.findFirstByShopIdAndProductIdOrderByCreatedAtDesc(shopId, productId);
    if (lot.isPresent() && lot.get().getSellingPrice() != null) {
      return lot.get().getSellingPrice();
    }
    return fallback;
  }

  private static GenerateBarcodesResponse.BarcodePoolItemDto toPoolDto(BarcodePool pool) {
    return new GenerateBarcodesResponse.BarcodePoolItemDto(
        pool.getId(),
        pool.getCode(),
        pool.getStatus(),
        pool.getProductId(),
        pool.getBatchId(),
        pool.getLabelName(),
        pool.getLabelCompany(),
        pool.getLabelPrice(),
        pool.getCreatedAt());
  }
}
