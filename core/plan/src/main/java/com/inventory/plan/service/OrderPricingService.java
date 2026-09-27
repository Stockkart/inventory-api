package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.service.voucher.VoucherService;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * The single place that turns a cart into money. Read-only: pricing a cart never reserves, debits
 * or writes anything.
 */
@Service
public class OrderPricingService {

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private AddOnCatalogueService addOnCatalogue;

  @Autowired
  private VoucherService voucherService;

  /** {@code shopId} is needed only to check vouchers the shop may use. */
  public QuoteResponse quote(String shopId, QuoteRequest request) {
    if (request == null || !StringUtils.hasText(request.getPlanCode())) {
      throw new ValidationException("planCode is required");
    }
    checkCart(request);
    String planCode = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
    Plan plan = planRepository.findByCode(planCode)
        .filter(EffectivePlanResolver::isActive)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", planCode));
    return toQuote(price(plan, request, shopId));
  }

  /**
   * Prices a cart for an already-resolved plan. Checkout calls this with the plan it will sell, so
   * the charge is exactly what a quote for the same cart shows.
   */
  public PricedCart price(Plan plan, QuoteRequest request, String shopId) {
    int durationMonths = checkCart(request);

    BigDecimal unitPrice = planPrice(plan);
    if (unitPrice == null || unitPrice.signum() <= 0) {
      throw new ValidationException("Plan " + plan.getPlanName() + " is not for sale");
    }
    List<OrderLine> items = new ArrayList<>();
    items.add(OrderLine.builder()
        .type(PricingConstants.ITEM_TYPE_PLAN)
        .code(plan.getCode())
        .name(plan.getPlanName())
        .quantity(1)
        .unitPrice(unitPrice)
        .discount(BigDecimal.ZERO)
        .lineTotal(unitPrice)
        .itemSource(PricingConstants.ITEM_SOURCE_MANUAL)
        .build());
    items.addAll(addOnLines(plan, request.getAddOns()));
    applyVouchers(plan, items, request.getVoucherCodes(), shopId);

    BigDecimal subtotal = sum(items.stream().map(line -> line.getUnitPrice()
        .multiply(BigDecimal.valueOf(line.getQuantity()))).toList());
    BigDecimal discountTotal = sum(items.stream().map(OrderLine::getDiscount).toList());
    // No wallet exists yet; applyWalletCredit is accepted so the request shape is final.
    BigDecimal walletCredit = BigDecimal.ZERO;
    return new PricedCart(plan, List.copyOf(items), subtotal, discountTotal, walletCredit,
        subtotal.subtract(discountTotal).subtract(walletCredit), durationMonths);
  }

  /**
   * One line per requested add-on, priced from the catalogue. Rejects unknown or hidden add-ons,
   * repeats, quantities the add-on does not allow, and add-ons the plan already includes (§10).
   */
  private List<OrderLine> addOnLines(Plan plan, List<QuoteRequest.AddOnLine> requested) {
    if (CollectionUtils.isEmpty(requested)) {
      return List.of();
    }
    Map<String, Integer> quantities = new LinkedHashMap<>();
    for (QuoteRequest.AddOnLine line : requested) {
      if (line == null || !StringUtils.hasText(line.getCode())) {
        throw new ValidationException("Add-on code is required");
      }
      String code = line.getCode().trim().toUpperCase(Locale.ROOT);
      int quantity = line.getQuantity() != null ? line.getQuantity() : 1;
      if (quantities.put(code, quantity) != null) {
        throw new ValidationException("Add-on " + code + " is listed more than once");
      }
    }
    Map<String, AddOn> catalogue = addOnCatalogue.byCode(quantities.keySet());
    List<OrderLine> lines = new ArrayList<>();
    quantities.forEach((code, quantity) -> {
      AddOn addOn = catalogue.get(code);
      if (addOn == null || !addOn.isActive()) {
        throw new ResourceNotFoundException("Add-on", "code", code);
      }
      checkQuantity(addOn, quantity);
      checkApplies(plan, addOn);
      lines.add(addOnLine(addOn, quantity, PricingConstants.ITEM_SOURCE_MANUAL));
    });
    return lines;
  }

  /**
   * Applies each voucher to its add-on line (§10 Voucher UX): a voucher for an add-on not in the
   * cart adds a line of its own ({@code itemSource = VOUCHER}); one voucher per line; never the plan
   * line (r4.8).
   */
  private void applyVouchers(Plan plan, List<OrderLine> items, List<String> rawCodes, String shopId) {
    if (CollectionUtils.isEmpty(rawCodes)) {
      return;
    }
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Vouchers can only be applied by a signed-in shop");
    }
    Set<String> codes = new LinkedHashSet<>();
    for (String raw : rawCodes) {
      String code = VoucherService.normalise(raw);
      if (code.isEmpty()) {
        throw new ValidationException("Voucher code is required");
      }
      if (!codes.add(code)) {
        throw new ValidationException("Voucher " + code + " is listed more than once");
      }
    }
    for (String code : codes) {
      AddOnVoucher voucher = voucherService.requireUsable(code, shopId);
      AddOn addOn = addOnCatalogue.byCode(List.of(voucher.getAddOnCode())).get(voucher.getAddOnCode());
      if (addOn == null || !addOn.isActive()) {
        throw notApplicable(code, "Voucher " + code + " is for an add-on that is no longer sold");
      }
      try {
        checkApplies(plan, addOn);
      } catch (ValidationException e) {
        throw notApplicable(code, e.getMessage());
      }
      OrderLine line = items.stream().filter(item -> addOn.getCode().equals(item.getCode())
          && !PricingConstants.ITEM_TYPE_PLAN.equals(item.getType())).findFirst().orElse(null);
      if (line == null) {
        line = addOnLine(addOn, Math.max(1, voucher.getQuantity()), PricingConstants.ITEM_SOURCE_VOUCHER);
        items.add(line);
      } else if (line.getVoucherCode() != null) {
        throw notApplicable(code, addOn.getName() + " already has voucher " + line.getVoucherCode());
      }
      BigDecimal discount = VoucherService.discountFor(voucher, line);
      line.setDiscount(discount);
      line.setLineTotal(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())).subtract(discount));
      line.setVoucherCode(code);
    }
  }

  private static VoucherRejectedException notApplicable(String code, String message) {
    return new VoucherRejectedException(code, VoucherRejection.NOT_APPLICABLE_TO_CART, message);
  }

  private static OrderLine addOnLine(AddOn addOn, int quantity, String itemSource) {
    return OrderLine.builder()
        .type(addOn.getGrantType() == AddOnGrantType.OCR_CREDITS
            ? PricingConstants.ITEM_TYPE_OCR_TOPUP
            : PricingConstants.ITEM_TYPE_ADDON)
        .code(addOn.getCode())
        .name(addOn.getName())
        .quantity(quantity)
        .unitPrice(addOn.getPrice())
        .discount(BigDecimal.ZERO)
        .lineTotal(addOn.getPrice().multiply(BigDecimal.valueOf(quantity)))
        .itemSource(itemSource)
        .build();
  }

  private static void checkQuantity(AddOn addOn, int quantity) {
    if (quantity < 1) {
      throw new ValidationException("Quantity for " + addOn.getCode() + " must be at least 1");
    }
    if (!addOn.isStackable() && quantity > 1) {
      throw new ValidationException(addOn.getName() + " can be bought once per order");
    }
    if (addOn.getMaxQuantity() != null && quantity > addOn.getMaxQuantity()) {
      throw new ValidationException("At most " + addOn.getMaxQuantity() + " of " + addOn.getName() + " per order");
    }
  }

  private static void checkApplies(Plan plan, AddOn addOn) {
    if (addOn.getGrantType() == AddOnGrantType.FEATURE
        && plan.getFeatures() != null && plan.getFeatures().contains(addOn.getGrantsFeature())) {
      throw new ValidationException(plan.getPlanName() + " already includes " + addOn.getName());
    }
    if (addOn.getGrantType() == AddOnGrantType.SEATS && plan.isUnlimited()) {
      throw new ValidationException(plan.getPlanName() + " already has unlimited users");
    }
  }

  /** Yearly price of a plan. Checkout charges exactly this, so a quote and its checkout agree. */
  public BigDecimal planPrice(Plan plan) {
    return plan.getArcPrice() != null ? plan.getArcPrice() : plan.getPrice();
  }

  private static QuoteResponse toQuote(PricedCart cart) {
    Instant now = Instant.now();
    return QuoteResponse.builder()
        .items(cart.items().stream().map(OrderPricingService::toQuoteItem).toList())
        .subtotal(cart.subtotal())
        .discountTotal(cart.discountTotal())
        .walletCredit(cart.walletCredit())
        .taxInclusive(true)
        .grandTotal(cart.grandTotal())
        .currency(PlanPaymentConstants.CURRENCY_INR)
        .durationMonths(cart.durationMonths())
        .pricingVersion(PricingConstants.PRICING_VERSION)
        .quotedAt(now)
        .expiresAt(now.plus(PricingConstants.QUOTE_TTL))
        .build();
  }

  static QuoteResponse.QuoteItem toQuoteItem(OrderLine line) {
    return QuoteResponse.QuoteItem.builder()
        .type(line.getType())
        .code(line.getCode())
        .name(line.getName())
        .quantity(line.getQuantity())
        .unitPrice(line.getUnitPrice())
        .discount(line.getDiscount())
        .lineTotal(line.getLineTotal())
        .itemSource(line.getItemSource())
        .voucherCode(line.getVoucherCode())
        .build();
  }

  /** Rejects carts that cannot be priced whatever the plan; returns the duration. */
  private static int checkCart(QuoteRequest request) {
    return resolveDuration(request.getDurationMonths());
  }

  private static int resolveDuration(Integer requested) {
    int months = requested != null ? requested : PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS;
    if (months != PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS) {
      throw new ValidationException("Plans are sold yearly; duration must be "
          + PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS + " months");
    }
    return months;
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
