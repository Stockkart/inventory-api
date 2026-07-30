package com.inventory.product.service;

import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.BarcodePool;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.enums.BarcodePoolStatus;
import com.inventory.product.domain.repository.BarcodePoolRepository;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.request.AttachBarcodeRequest;
import com.inventory.product.rest.dto.request.BarcodeLabelsRequest;
import com.inventory.product.rest.dto.request.GenerateBarcodesRequest;
import com.inventory.product.rest.dto.response.BarcodeLabelsResponse;
import com.inventory.product.rest.dto.response.BarcodePoolListResponse;
import com.inventory.product.rest.dto.response.GenerateBarcodesResponse;
import com.inventory.product.validation.ProductValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@Transactional
public class BarcodeService {

  private static final int MAX_GENERATE = 500;
  private static final int DEFAULT_LIST_LIMIT = 100;
  private static final int MAX_LIST_LIMIT = 500;

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

    if (pool != null) {
      if (pool.getStatus() == BarcodePoolStatus.ATTACHED
          && StringUtils.hasText(pool.getProductId())
          && !pool.getProductId().equals(product.getId())) {
        throw new ResourceExistsException("Barcode", "code", normalized);
      }
    }

    productService.updateBarcodeInPlace(product.getId(), shopId, normalized);

    Instant now = Instant.now();
    if (pool == null) {
      pool = new BarcodePool();
      pool.setShopId(shopId);
      pool.setCode(normalized);
      pool.setCreatedAt(now);
    }
    pool.setStatus(BarcodePoolStatus.ATTACHED);
    pool.setProductId(product.getId());
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
   * When a product is saved with a barcode that exists in the UNUSED pool, mark it ATTACHED.
   * Also rejects barcodes already attached to another product.
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
    if (pool.getStatus() == BarcodePoolStatus.ATTACHED
        && StringUtils.hasText(pool.getProductId())
        && !pool.getProductId().equals(productId)) {
      throw new ResourceExistsException("Barcode", "code", normalized);
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
    Map<String, BarcodeLabelsResponse.BarcodeLabelDto> byCode = new LinkedHashMap<>();

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
        byCode.put(
            product.getBarcode(),
            new BarcodeLabelsResponse.BarcodeLabelDto(
                product.getBarcode(),
                product.getName(),
                product.getCompanyName(),
                resolvePrice(shopId, product.getId(), null),
                product.getId()));
      }
    }

    if (request.getCodes() != null) {
      for (String code : request.getCodes()) {
        String normalized = productValidator.normalizeBarcode(code);
        if (normalized == null) {
          continue;
        }
        if (byCode.containsKey(normalized)) {
          continue;
        }
        Optional<Product> productOpt = productRepository.findByShopIdAndBarcode(shopId, normalized);
        if (productOpt.isPresent()) {
          Product product = productOpt.get();
          byCode.put(
              normalized,
              new BarcodeLabelsResponse.BarcodeLabelDto(
                  normalized,
                  product.getName(),
                  product.getCompanyName(),
                  resolvePrice(shopId, product.getId(), null),
                  product.getId()));
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
        } else {
          byCode.put(
              normalized,
              new BarcodeLabelsResponse.BarcodeLabelDto(normalized, null, null, null, null));
        }
      }
    }

    return new BarcodeLabelsResponse(new ArrayList<>(byCode.values()));
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
