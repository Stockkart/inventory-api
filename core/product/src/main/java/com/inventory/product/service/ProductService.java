package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.repository.ProductRepository;
import com.inventory.product.rest.dto.response.ProductSuggestionDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

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
   * <ul>
   *   <li>If {@code requestedProductId} points to a product whose identity still matches the
   *       submitted fields, reuse it.</li>
   *   <li>If it points to a product but an identity field changed, fork a new product.</li>
   *   <li>If no product id is supplied, match an existing product by shop identity key, else
   *       create a new one.</li>
   * </ul>
   *
   * @param requestedProductId product id selected in the UI (may be null/blank)
   * @param inventory inventory entity with normalized identity fields already populated
   * @param shopId owning shop
   * @return the id of the product this inventory lot should link to
   */
  public String resolveForRegistration(String requestedProductId, Inventory inventory, String shopId) {
    Product candidate = fromInventory(inventory, shopId);

    if (StringUtils.hasText(requestedProductId)) {
      Product existing = productRepository.findByIdAndShopId(requestedProductId.trim(), shopId)
          .orElse(null);
      if (existing != null) {
        if (identityMatches(existing, candidate)) {
          return existing.getId();
        }
        log.info("Product identity changed for {} in shop {}; forking new product",
            existing.getId(), shopId);
        return persistNew(candidate).getId();
      }
    }

    Product matched = findByIdentity(candidate, shopId);
    if (matched != null) {
      return matched.getId();
    }
    return persistNew(candidate).getId();
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
    return productRepository.save(candidate);
  }

  /** True when every catalog identity field is equal (fork otherwise). */
  private static boolean identityMatches(Product a, Product b) {
    return identityKey(a).equals(identityKey(b));
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
