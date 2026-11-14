package com.inventory.product.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.domain.model.enums.ItemType;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Owns shop-scoped catalog {@link Product} lifecycle: typeahead suggest and the
 * reuse / fork / create resolution used during inventory registration.
 */
@Slf4j
@Service
@Transactional
public class ProductService {

  private static final int SUGGEST_LIMIT = 10;
  /** Read past {@link #SUGGEST_LIMIT} so copies folded into one row still leave ten rows. */
  private static final int SUGGEST_FETCH_LIMIT = 50;
  private static final String KEY_SEPARATOR = "\u0001";
  private static final String MEDICAL = "MEDICAL";
  /** Business types written before the medical vertical settled on {@code MEDICAL}. */
  private static final Set<String> MEDICAL_ALIASES = Set.of("PHARMACEUTICAL", "PHARMACY");

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private ProductValidator productValidator;

  @Autowired
  @Lazy
  private BarcodeService barcodeService;

  /**
   * Typeahead for the registration screen. Empty/blank query returns no rows.
   *
   * <p>Copies of one catalog product are offered once, as the copy registration would reuse, so
   * the user cannot keep picking a stale copy. Copies with different barcodes stay separate rows:
   * a different barcode is a different product.
   */
  @Transactional(readOnly = true)
  public List<ProductSuggestionDto> suggest(String shopId, String query) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(query)) {
      return Collections.emptyList();
    }
    List<Product> found = productRepository.suggestByShopIdAndQuery(
        shopId, query.trim(), PageRequest.of(0, SUGGEST_FETCH_LIMIT));
    return onePerProduct(found).stream()
        .limit(SUGGEST_LIMIT)
        .map(ProductService::toSuggestion)
        .toList();
  }

  /** One row per catalog product: each barcode once, and blank copies only when none has one. */
  private static List<Product> onePerProduct(List<Product> found) {
    Map<String, List<Product>> sameItems = new LinkedHashMap<>();
    for (Product p : found) {
      sameItems.computeIfAbsent(sameItemKey(p), k -> new ArrayList<>()).add(p);
    }
    List<Product> rows = new ArrayList<>();
    for (List<Product> copies : sameItems.values()) {
      Map<String, List<Product>> byBarcode = new LinkedHashMap<>();
      for (Product p : copies) {
        String barcode = nz(p.getBarcode());
        if (barcode != null) {
          byBarcode.computeIfAbsent(barcode, k -> new ArrayList<>()).add(p);
        }
      }
      if (byBarcode.isEmpty()) {
        rows.add(preferredCopy(copies));
      } else {
        byBarcode.values().forEach(sameCode -> rows.add(preferredCopy(sameCode)));
      }
    }
    return rows;
  }

  /** Full identity for prefill when the UI resolves a selected suggestion. */
  @Transactional(readOnly = true)
  public ProductSuggestionDto getById(String shopId, String id) {
    Product product = productRepository
        .findByIdAndShopId(id, shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Product", "id", id));
    return toSuggestion(product);
  }

  @Transactional(readOnly = true)
  public Optional<Product> findInShop(String shopId, String productId) {
    if (!StringUtils.hasText(shopId) || !StringUtils.hasText(productId)) {
      return Optional.empty();
    }
    return productRepository.findByIdAndShopId(productId.trim(), shopId);
  }

  /** Every shop product carrying this exact barcode (several products may share one). */
  @Transactional(readOnly = true)
  public List<Product> findAllByBarcode(String shopId, String barcode) {
    String normalized = productValidator.normalizeBarcode(barcode);
    if (!StringUtils.hasText(shopId) || normalized == null) {
      return Collections.emptyList();
    }
    return productRepository.findAllByShopIdAndBarcode(shopId, normalized);
  }

  @Transactional(readOnly = true)
  public List<ProductSuggestionDto> suggestionsByBarcode(String shopId, String barcode) {
    return findAllByBarcode(shopId, barcode).stream().map(ProductService::toSuggestion).toList();
  }

  /**
   * Resolve the {@link Product} for a registration line and return its id.
   *
   * <p>The shop's existing product is reused whenever the entry describes the same item
   * ({@link #sameItemKey}: name, company, description, business type, HSN, unit, item type and
   * pack size, compared loosely) and the barcodes agree. A blank barcode on either side agrees, so
   * an entry without a barcode reuses the product that has one, and an entry with a barcode gives
   * it to a product that had none. Barcodes are not unique, so the same code on a different pack
   * size or variant still becomes its own product rather than joining one whose packaging would
   * misread the stock count; two different barcodes are two products.
   *
   * <p>A picked product ({@code requestedProductId}) the entry matches loosely is kept and takes
   * the entry's barcode and spellings ({@link #correctInPlace}). One that no longer matches is not
   * forked blindly: the shop is searched for a matching product first, and a new one is created
   * only when none exists.
   */
  public String resolveForRegistration(String requestedProductId, Inventory inventory, String shopId) {
    return resolveForRegistration(requestedProductId, inventory, shopId, false);
  }

  /**
   * As {@link #resolveForRegistration(String, Inventory, String)}; {@code barcodeCleared} is true
   * when a lot edit cleared the barcode ({@code clearFields}), so a blank barcode empties the picked
   * product's. Otherwise a blank barcode means none was given and the product keeps its own.
   */
  public String resolveForRegistration(
      String requestedProductId, Inventory inventory, String shopId, boolean barcodeCleared) {
    normalizeInventoryBarcode(inventory);
    productValidator.validateBarcode(inventory.getBarcode());

    Product candidate = fromInventory(inventory, shopId);

    if (StringUtils.hasText(requestedProductId)) {
      Product existing = productRepository.findByIdAndShopId(requestedProductId.trim(), shopId)
          .orElse(null);
      if (existing != null) {
        if (sameItemKey(existing).equals(sameItemKey(candidate))) {
          return correctInPlace(existing, candidate, shopId, barcodeCleared);
        }
        log.info("Product identity changed for {} in shop {}; looking for a matching product",
            existing.getId(), shopId);
      }
    }

    Product matched = findByIdentity(candidate, shopId);
    if (matched != null) {
      return reuse(matched, candidate, shopId);
    }
    return persistNew(candidate).getId();
  }

  /**
   * The entry is the picked product, possibly with corrections that apply to the product itself:
   * <ul>
   *   <li>Barcode: a new one replaces it, unless another copy of the same item already carries that
   *       code (that copy is reused). A blank one empties it only when {@code barcodeCleared}.
   *   <li>Company, description, unit: the entry's spelling.
   *   <li>Business type and item type: only upgraded from an older spelling ({@code PHARMACEUTICAL},
   *       {@code PHARMACY}, a missing item type), never downgraded to one.
   * </ul>
   */
  private String correctInPlace(
      Product existing, Product candidate, String shopId, boolean barcodeCleared) {
    String oldBarcode = nz(existing.getBarcode());
    String newBarcode = nz(candidate.getBarcode());
    if (newBarcode == null && !barcodeCleared) {
      newBarcode = oldBarcode;
    }
    if (newBarcode != null && !newBarcode.equals(oldBarcode)) {
      Product holder = findByIdentity(candidate, shopId);
      if (holder != null && !holder.getId().equals(existing.getId())
          && newBarcode.equals(nz(holder.getBarcode()))) {
        return holder.getId();
      }
    }
    String businessType = isOlderSpelling(existing.getBusinessType())
        && !isOlderSpelling(candidate.getBusinessType())
        ? candidate.getBusinessType()
        : existing.getBusinessType();
    ItemType itemType = existing.getItemType() != null
        ? existing.getItemType()
        : candidate.getItemType();
    boolean changed = !Objects.equals(oldBarcode, newBarcode)
        || !Objects.equals(nz(existing.getCompanyName()), nz(candidate.getCompanyName()))
        || !Objects.equals(nz(existing.getDescription()), nz(candidate.getDescription()))
        || !Objects.equals(nz(existing.getBaseUnit()), nz(candidate.getBaseUnit()))
        || !Objects.equals(existing.getBusinessType(), businessType)
        || existing.getItemType() != itemType;
    if (!changed) {
      return existing.getId();
    }
    existing.setBarcode(newBarcode);
    existing.setCompanyName(candidate.getCompanyName());
    existing.setDescription(candidate.getDescription());
    existing.setBusinessType(businessType);
    existing.setBaseUnit(candidate.getBaseUnit());
    existing.setItemType(itemType);
    existing.setUpdatedAt(Instant.now());
    productRepository.save(existing);
    if (!Objects.equals(oldBarcode, newBarcode)) {
      barcodeService.claimPoolForProduct(shopId, existing.getId(), existing.getBarcode());
    }
    return existing.getId();
  }

  /** Reuse {@code existing}; an entry barcode fills a product that has none, never replaces one. */
  private String reuse(Product existing, Product candidate, String shopId) {
    String incoming = nz(candidate.getBarcode());
    if (incoming != null && nz(existing.getBarcode()) == null) {
      existing.setBarcode(incoming);
      existing.setUpdatedAt(Instant.now());
      productRepository.save(existing);
      barcodeService.claimPoolForProduct(shopId, existing.getId(), incoming);
    }
    return existing.getId();
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
    product.setBarcode(normalized);
    product.setUpdatedAt(Instant.now());
    return productRepository.save(product);
  }

  /**
   * The shop product the entry is, or null. Among several copies the one carrying the entry's own
   * barcode wins, then {@link #preferredCopy}.
   */
  private Product findByIdentity(Product candidate, String shopId) {
    List<Product> sameName =
        productRepository.findByShopIdAndNormalizedName(shopId, candidate.getNormalizedName());
    List<Product> matches = sameName.stream().filter(p -> sameItem(p, candidate)).toList();
    if (matches.isEmpty()) {
      return null;
    }
    String barcode = nz(candidate.getBarcode());
    List<Product> sameCode = barcode == null
        ? List.of()
        : matches.stream().filter(p -> barcode.equals(nz(p.getBarcode()))).toList();
    return preferredCopy(sameCode.isEmpty() ? matches : sameCode);
  }

  /** The copy to reuse or offer: one with a barcode first, then the oldest. */
  private static Product preferredCopy(List<Product> copies) {
    return copies.stream()
        .min(Comparator.comparing((Product p) -> nz(p.getBarcode()) == null)
            .thenComparing(Product::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
        .orElseThrow();
  }

  private Product persistNew(Product candidate) {
    Instant now = Instant.now();
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    Product saved = productRepository.save(candidate);
    barcodeService.claimPoolForProduct(saved.getShopId(), saved.getId(), saved.getBarcode());
    return saved;
  }

  /** Same item ({@link #sameItemKey}) and barcodes that agree: equal, or blank on either side. */
  private static boolean sameItem(Product a, Product b) {
    if (!sameItemKey(a).equals(sameItemKey(b))) {
      return false;
    }
    String codeA = nz(a.getBarcode());
    String codeB = nz(b.getBarcode());
    return codeA == null || codeB == null || codeA.equals(codeB);
  }

  /**
   * Every catalog identity field except the barcode, compared loosely: company, description and
   * unit ignore case and repeated spaces; the business type ignores case and reads the older
   * spellings {@code PHARMACEUTICAL} and {@code PHARMACY} as {@code MEDICAL}; a missing item type
   * is {@code NORMAL}. Equal keys describe the same item.
   */
  private static String sameItemKey(Product p) {
    return String.join(
        KEY_SEPARATOR,
        String.valueOf(p.getNormalizedName()),
        String.valueOf(looseText(p.getCompanyName())),
        String.valueOf(looseText(p.getDescription())),
        String.valueOf(businessTypeKey(p.getBusinessType())),
        String.valueOf(nz(p.getHsn())),
        String.valueOf(looseText(p.getBaseUnit())),
        String.valueOf(p.getItemType() != null ? p.getItemType() : ItemType.NORMAL),
        String.valueOf(p.getItemTypeDegree()),
        String.valueOf(packFactor(p.getUnitConversions())));
  }

  private static String looseText(String value) {
    String trimmed = nz(value);
    return trimmed == null ? null : trimmed.replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
  }

  /** Blank, or a business type written before the medical vertical settled on {@code MEDICAL}. */
  private static boolean isOlderSpelling(String businessType) {
    String type = looseText(businessType);
    return type == null || MEDICAL_ALIASES.contains(type);
  }

  private static String businessTypeKey(String businessType) {
    String type = looseText(businessType);
    return type != null && MEDICAL_ALIASES.contains(type) ? MEDICAL : type;
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
