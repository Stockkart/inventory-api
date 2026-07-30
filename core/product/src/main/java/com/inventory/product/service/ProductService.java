package com.inventory.product.service;

import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.response.ProductSuggestionDto;
import com.inventory.product.validation.ProductValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Owns shop-scoped catalog {@link Product} lifecycle: typeahead suggest and the
 * reuse / fork / create resolution used during inventory registration.
 */
@Slf4j
@Service
@Transactional
public class ProductService {

  private static final int SUGGEST_LIMIT = 10;

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private ProductValidator productValidator;

  @Autowired
  @Lazy
  private BarcodeService barcodeService;

  /** Typeahead for the registration screen. Empty/blank query returns no rows. */
  @Transactional(readOnly = true)
  public List<ProductSuggestionDto> suggest(String shopId, String query) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(query)) {
      return Collections.emptyList();
    }
    return productRepository
        .suggestByShopIdAndQuery(shopId, query.trim(), PageRequest.of(0, SUGGEST_LIMIT))
        .stream()
        .map(ProductService::toSuggestion)
        .toList();
  }

  /** Full identity for prefill when the UI resolves a selected suggestion. */
  @Transactional(readOnly = true)
  public ProductSuggestionDto getById(String shopId, String id) {
    Product product = productRepository
        .findByIdAndShopId(id, shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    return toSuggestion(product);
  }

  /**
   * Resolve the {@link Product} for a registration line and return its id.
   *
   * <p>When a barcode is present and already owned by a shop product, that product is reused
   * (stock-in again for the same SKU). Barcodes stay unique per shop — never fork into a
   * duplicate code.
   */
  public String resolveForRegistration(String requestedProductId, Inventory inventory, String shopId) {
    normalizeInventoryBarcode(inventory);
    productValidator.validateBarcode(inventory.getBarcode());

    Product candidate = fromInventory(inventory, shopId);

    // Barcode is the stable shop-unique key: re-registration with the same code reuses the owner.
    if (StringUtils.hasText(candidate.getBarcode())) {
      Optional<Product> byBarcode =
          productRepository.findByShopIdAndBarcode(shopId, candidate.getBarcode());
      if (byBarcode.isPresent()) {
        Product owner = byBarcode.get();
        if (StringUtils.hasText(requestedProductId)
            && !requestedProductId.trim().equals(owner.getId())) {
          throw new ResourceExistsException("Barcode", "code", candidate.getBarcode());
        }
        log.debug(
            "Reusing product {} for barcode {} in shop {}",
            owner.getId(),
            candidate.getBarcode(),
            shopId);
        return owner.getId();
      }
    }

    if (StringUtils.hasText(requestedProductId)) {
      Product existing = productRepository.findByIdAndShopId(requestedProductId.trim(), shopId)
          .orElse(null);
      if (existing != null) {
        if (identityMatches(existing, candidate)) {
          return existing.getId();
        }
        // Only-barcode change on an existing product (new free code): update in place.
        if (onlyBarcodeChanged(existing, candidate)) {
          assertBarcodeAvailable(shopId, candidate.getBarcode(), existing.getId());
          existing.setBarcode(candidate.getBarcode());
          existing.setUpdatedAt(Instant.now());
          productRepository.save(existing);
          barcodeService.claimPoolForProduct(shopId, existing.getId(), existing.getBarcode());
          return existing.getId();
        }
        assertBarcodeAvailable(shopId, candidate.getBarcode(), null);
        log.info("Product identity changed for {} in shop {}; forking new product",
            existing.getId(), shopId);
        return persistNew(candidate).getId();
      }
    }

    Product matched = findByIdentity(candidate, shopId);
    if (matched != null) {
      return matched.getId();
    }
    assertBarcodeAvailable(shopId, candidate.getBarcode(), null);
    return persistNew(candidate).getId();
  }

  /**
   * Set barcode on an existing product without forking. Used by pool attach and regenerate.
   */
  public Product updateBarcodeInPlace(String productId, String shopId, String barcode) {
    Product product = productRepository
        .findByIdAndShopId(productId, shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Product", "id", productId));
    String normalized = productValidator.normalizeBarcode(barcode);
    productValidator.validateBarcode(normalized);
    assertBarcodeAvailable(shopId, normalized, productId);
    product.setBarcode(normalized);
    product.setUpdatedAt(Instant.now());
    return productRepository.save(product);
  }

  /**
   * Reject when another product in the shop already owns this barcode.
   *
   * @param excludeProductId product id that may keep this barcode (null when creating)
   */
  public void assertBarcodeAvailable(String shopId, String barcode, String excludeProductId) {
    String normalized = productValidator.normalizeBarcode(barcode);
    if (normalized == null) {
      return;
    }
    productRepository.findByShopIdAndBarcode(shopId, normalized).ifPresent(existing -> {
      if (excludeProductId == null || !excludeProductId.equals(existing.getId())) {
        throw new ResourceExistsException("Barcode", "code", normalized);
      }
    });
  }

  private Product findByIdentity(Product candidate, String shopId) {
    List<Product> sameName =
        productRepository.findByShopIdAndNormalizedName(shopId, candidate.getNormalizedName());
    String candidateKey = identityKey(candidate);
    for (Product p : sameName) {
      if (candidateKey.equals(identityKey(p))) {
        return p;
      }
    }
    return null;
  }

  /**
   * Canonical identity key for a live inventory row. Two rows sharing this key belong to the same
   * catalog product. Used by the backfill to group existing inventory (dry-run preview) and mirrors
   * the fields compared during {@link #resolveForRegistration}.
   */
  public String identityKey(Inventory inventory, String shopId) {
    return identityKey(fromInventory(inventory, shopId));
  }

  private Product persistNew(Product candidate) {
    Instant now = Instant.now();
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    Product saved = productRepository.save(candidate);
    barcodeService.claimPoolForProduct(saved.getShopId(), saved.getId(), saved.getBarcode());
    return saved;
  }

  /** True when every catalog identity field is equal (fork otherwise). */
  private static boolean identityMatches(Product a, Product b) {
    return identityKey(a).equals(identityKey(b));
  }

  /** True when all identity fields match except barcode. */
  private static boolean onlyBarcodeChanged(Product existing, Product candidate) {
    Product withoutBarcode = copyIdentity(existing);
    withoutBarcode.setBarcode(nz(candidate.getBarcode()));
    Product candidateNorm = copyIdentity(candidate);
    return identityKey(withoutBarcode).equals(identityKey(candidateNorm))
        && !java.util.Objects.equals(nz(existing.getBarcode()), nz(candidate.getBarcode()));
  }

  private static Product copyIdentity(Product src) {
    Product p = new Product();
    p.setNormalizedName(src.getNormalizedName());
    p.setCompanyName(src.getCompanyName());
    p.setBarcode(src.getBarcode());
    p.setDescription(src.getDescription());
    p.setBusinessType(src.getBusinessType());
    p.setHsn(src.getHsn());
    p.setBaseUnit(src.getBaseUnit());
    p.setItemType(src.getItemType());
    p.setItemTypeDegree(src.getItemTypeDegree());
    p.setUnitConversions(src.getUnitConversions());
    return p;
  }

  /** Stable string over all catalog identity fields; equal keys mean the same product. */
  private static String identityKey(Product p) {
    return String.join(
        "\u0001",
        String.valueOf(p.getNormalizedName()),
        String.valueOf(nz(p.getCompanyName())),
        String.valueOf(nz(p.getBarcode())),
        String.valueOf(nz(p.getDescription())),
        String.valueOf(nz(p.getBusinessType())),
        String.valueOf(nz(p.getHsn())),
        String.valueOf(nz(p.getBaseUnit())),
        String.valueOf(p.getItemType()),
        String.valueOf(p.getItemTypeDegree()),
        String.valueOf(packFactor(p.getUnitConversions())));
  }

  private void normalizeInventoryBarcode(Inventory inventory) {
    inventory.setBarcode(productValidator.normalizeBarcode(inventory.getBarcode()));
  }

  private static Product fromInventory(Inventory inventory, String shopId) {
    Product p = new Product();
    p.setShopId(shopId);
    p.setBarcode(inventory.getBarcode());
    p.setName(inventory.getName());
    p.setNormalizedName(normalizeName(inventory.getName()));
    p.setDescription(inventory.getDescription());
    p.setCompanyName(inventory.getCompanyName());
    p.setBusinessType(inventory.getBusinessType());
    p.setItemType(inventory.getItemType());
    p.setItemTypeDegree(inventory.getItemTypeDegree());
    p.setBaseUnit(inventory.getBaseUnit());
    p.setUnitConversions(inventory.getUnitConversions());
    p.setHsn(inventory.getHsn());
    return p;
  }

  private static ProductSuggestionDto toSuggestion(Product p) {
    return new ProductSuggestionDto(
        p.getId(),
        p.getBarcode(),
        p.getName(),
        p.getDescription(),
        p.getCompanyName(),
        p.getBusinessType(),
        p.getItemType(),
        p.getItemTypeDegree(),
        p.getBaseUnit(),
        p.getUnitConversions(),
        p.getHsn());
  }

  private static String normalizeName(String name) {
    return name == null ? null : name.trim().toLowerCase();
  }

  private static int packFactor(UnitConversion conversion) {
    if (conversion == null || conversion.getFactor() == null || conversion.getFactor() <= 0) {
      return 1;
    }
    return conversion.getFactor();
  }

  private static String nz(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
