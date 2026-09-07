package com.inventory.product.tax;

import com.inventory.common.tax.GstMath;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.repository.PricingRepository;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.domain.repository.InventoryRepository;
import com.inventory.product.domain.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks a product's GST rate against what the rest of the shop's catalogue says about its HSN.
 *
 * <p>Goods under one HSN attract one rate. Where a shop's own records disagree about that, one of
 * them is wrong, and this finds which is the odd one out. It is deliberately built on the shop's
 * own data rather than a rate table: a wrong rate keyed once is a slip, and the twenty products
 * already recorded correctly under that HSN are the best evidence available of what the right
 * answer is.
 *
 * <p>This is the class of error nothing else catches. A header that agrees with a wrong rate
 * reconciles perfectly -- the invoice adds up, the total matches the bill, and the tax is still
 * wrong. Two such invoices in this shop understated and overstated GST by hundreds of rupees each,
 * and both were only found by reading the paper.
 */
@Service
@Slf4j
public class HsnRateConsistency {

  /**
   * How many other products must agree before their rate is treated as the shop's answer.
   *
   * <p>Two, because one other product is just as likely to be the mistake. It stays quiet rather
   * than guess -- a warning that fires on thin evidence is one operators learn to dismiss.
   */
  private static final int MIN_AGREEING_PRODUCTS = 2;

  @Autowired private ProductRepository productRepository;
  @Autowired private InventoryRepository inventoryRepository;
  @Autowired private PricingRepository pricingRepository;

  /** A rate that disagrees with the rest of its HSN, and what the rest of it says. */
  public record Conflict(String hsn, BigDecimal recordedRate, BigDecimal prevailingRate,
                         int agreeingProducts) {

    public String describe(String productName) {
      return String.format(
          "%s is recorded at %s%% GST, but %d other product%s under HSN %s %s at %s%%.",
          productName, strip(recordedRate), agreeingProducts, agreeingProducts == 1 ? "" : "s",
          hsn, agreeingProducts == 1 ? "is" : "are", strip(prevailingRate));
    }

    private static String strip(BigDecimal value) {
      return value.stripTrailingZeros().toPlainString();
    }
  }

  /**
   * Whether {@code ratePct} disagrees with the rest of the shop's products under {@code hsn}.
   *
   * <p>Empty when the HSN is unknown, when too few other products carry it to be evidence, or
   * when any of them already agrees with the rate given -- an HSN that legitimately spans two
   * rates says nothing about which one a given product should be on.
   */
  public Optional<Conflict> check(String shopId, String hsn, BigDecimal ratePct) {
    if (!StringUtils.hasText(hsn) || "0".equals(hsn) || ratePct == null) {
      return Optional.empty();
    }
    try {
      Map<BigDecimal, Integer> byRate = ratesUnderHsn(shopId, hsn, ratePct);
      if (byRate.isEmpty()) {
        return Optional.empty();
      }
      // Any agreement at all settles it: the rate given is one the shop already uses here.
      for (BigDecimal seen : byRate.keySet()) {
        if (seen.compareTo(ratePct) == 0) {
          return Optional.empty();
        }
      }
      Map.Entry<BigDecimal, Integer> prevailing = byRate.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .orElse(null);
      if (prevailing == null || prevailing.getValue() < MIN_AGREEING_PRODUCTS) {
        return Optional.empty();
      }
      return Optional.of(
          new Conflict(hsn, ratePct, prevailing.getKey(), prevailing.getValue()));
    } catch (RuntimeException e) {
      // Advisory only. A check that cannot run is not a reason to fail the work it was checking.
      log.warn("HSN rate check failed for shop {} hsn {}", shopId, hsn, e);
      return Optional.empty();
    }
  }

  /** Distinct GST rates carried by other products under this HSN, and how many carry each. */
  private Map<BigDecimal, Integer> ratesUnderHsn(String shopId, String hsn, BigDecimal ratePct) {
    List<Product> siblings = productRepository.findByShopIdAndHsn(shopId, hsn);
    if (siblings.size() < MIN_AGREEING_PRODUCTS) {
      return Map.of();
    }
    List<String> productIds = siblings.stream().map(Product::getId).collect(Collectors.toList());
    List<Inventory> lots = inventoryRepository.findByShopIdAndProductIdIn(shopId, productIds);

    Set<String> pricingIds = lots.stream()
        .map(Inventory::getPricingId)
        .filter(StringUtils::hasText)
        .collect(Collectors.toSet());
    if (pricingIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Pricing> pricingById = new HashMap<>();
    pricingRepository.findAllById(pricingIds).forEach(p -> pricingById.put(p.getId(), p));

    // Counted per product, not per lot: a product stocked twenty times would otherwise outvote
    // twenty products stocked once.
    Map<String, BigDecimal> rateByProduct = new HashMap<>();
    for (Inventory lot : lots) {
      Pricing pricing = pricingById.get(lot.getPricingId());
      if (pricing == null) continue;
      BigDecimal rate = GstMath.parseRatePct(pricing.getSgst())
          .add(GstMath.parseRatePct(pricing.getCgst()));
      rateByProduct.putIfAbsent(lot.getProductId(), rate);
    }

    Map<BigDecimal, Integer> byRate = new HashMap<>();
    for (BigDecimal rate : new ArrayList<>(rateByProduct.values())) {
      BigDecimal key = byRate.keySet().stream()
          .filter(k -> k.compareTo(rate) == 0)
          .findFirst()
          .orElse(rate);
      byRate.merge(key, 1, Integer::sum);
    }
    return byRate;
  }
}
