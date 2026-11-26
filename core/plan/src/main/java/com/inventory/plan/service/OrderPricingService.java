package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.PaidTerm;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.model.VoucherRejection;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.exception.VoucherRejectedException;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.AddOnResponse;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.utils.PlanVoucherPricing;
import com.inventory.plan.service.voucher.VoucherService;
import com.inventory.plan.service.wallet.WalletService;
import com.inventory.plan.utils.AddOnEligibility;
import com.inventory.plan.utils.CartUtils;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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

  @Autowired
  private WalletService walletService;

  @Autowired
  private EntitlementService entitlementService;

  @Autowired
  private ShopAddOnService shopAddOnService;

  @Autowired
  private PlanService planService;

  /**
   * {@code shopId} is needed to check vouchers the shop may use, for an add-on-only cart (no
   * plan, at least one add-on) to find the plan the add-ons are priced against, and to refuse a
   * different plan while the shop's paid plan still has days left ({@code PLAN_CHANGE_NOT_ALLOWED}),
   * as checkout does.
   */
  public QuoteResponse quote(String shopId, QuoteRequest request) {
    if (CartUtils.isAddOnOnly(request)) {
      return toQuote(priceAddOnOnly(shopId, request));
    }
    if (!CartUtils.hasPlan(request)) {
      throw new ValidationException(PricingConstants.MSG_EMPTY_CART);
    }
    checkCart(request);
    String planCode = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
    Plan plan = planRepository.findByCode(planCode)
        .filter(EffectivePlanResolver::isActive)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", planCode));
    if (StringUtils.hasText(shopId)) {
      planService.requirePlanChangeAllowed(shopId, plan);
    }
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
    // A plan cart may re-buy an add-on the shop holds: the new grant runs to the new term's end.
    items.addAll(addOnLines(plan, request.getAddOns(), Set.of()));
    applyVouchers(plan, items, request.getVoucherCodes(), shopId, Set.of());
    // A voucher-priced plan keeps the voucher floor (base price, or Rs 1) against wallet credit too.
    boolean vouchered = items.stream().anyMatch(item -> PricingConstants.ITEM_TYPE_PLAN.equals(item.getType())
        && item.getVoucherCode() != null);
    BigDecimal planFloor = vouchered ? PlanVoucherPricing.floor(plan) : plan.getBasePrice();
    return total(plan, items, request, shopId, planFloor, durationMonths, false, null);
  }

  /**
   * Prices add-ons bought on their own against the shop's current paid plan (§3.3): full price, no
   * proration, no plan line and so no plan floor. {@code durationMonths} is ignored; annual add-ons
   * end with the current term ({@link PricedCart#termEndsAt()}, stored on the order), and OCR
   * credits never expire. A non-stackable add-on the shop already holds live is rejected.
   */
  public PricedCart priceAddOnOnly(String shopId, QuoteRequest request) {
    if (!CartUtils.isAddOnOnly(request)) {
      throw new ValidationException(PricingConstants.MSG_EMPTY_CART);
    }
    PaidTerm term = entitlementService.paidTerm(shopId)
        .orElseThrow(() -> new ValidationException(PricingConstants.MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN));
    Plan plan = planRepository.findById(term.planId())
        .orElseThrow(() -> new ValidationException(PricingConstants.MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN));
    Set<String> held = heldOnce(shopId);
    List<OrderLine> items = new ArrayList<>(addOnLines(plan, request.getAddOns(), held));
    applyVouchers(plan, items, request.getVoucherCodes(), shopId, held);
    return total(plan, items, request, shopId, null, 0, true, term.expiresAt());
  }

  /**
   * The add-ons on sale with whether this shop can buy each on its own now (add-on-only top-up):
   * the same rules as {@link #priceAddOnOnly}. Without an active paid plan none can be.
   */
  public List<AddOnResponse> addOnOptionsForShop(String shopId) {
    List<AddOn> addOns = addOnCatalogue.activeAddOns();
    Optional<Plan> plan = entitlementService.paidTerm(shopId).flatMap(term -> planRepository.findById(term.planId()));
    if (plan.isEmpty()) {
      Optional<String> reason = Optional.of(PricingConstants.MSG_ADD_ON_ONLY_NEEDS_PAID_PLAN);
      return addOns.stream().map(addOn -> addOnCatalogue.toOption(addOn, reason)).toList();
    }
    Set<String> held = heldOnce(shopId);
    return addOns.stream()
        .map(addOn -> addOnCatalogue.toOption(addOn, AddOnEligibility.ineligibleReason(plan.get(), addOn, held)))
        .toList();
  }

  /** The add-ons on sale with whether each can go in a cart that buys {@code planCode}. */
  public List<AddOnResponse> addOnOptionsForPlan(String planCode) {
    String code = planCode.trim().toUpperCase(Locale.ROOT);
    Plan plan = planRepository.findByCode(code)
        .filter(EffectivePlanResolver::isActive)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", code));
    return addOnCatalogue.activeAddOns().stream()
        .map(addOn -> addOnCatalogue.toOption(addOn, AddOnEligibility.ineligibleReason(plan, addOn, Set.of())))
        .toList();
  }

  /** Codes of live add-ons the shop holds that cannot be bought again on top (not stackable). */
  private Set<String> heldOnce(String shopId) {
    Set<String> codes = new HashSet<>();
    for (ShopAddOn held : shopAddOnService.live(shopId, Instant.now())) {
      if (held.getGrantType() != AddOnGrantType.OCR_CREDITS && held.getAddOnCode() != null) {
        codes.add(held.getAddOnCode());
      }
    }
    return codes;
  }

  private PricedCart total(Plan plan, List<OrderLine> items, QuoteRequest request, String shopId,
      BigDecimal planFloor, int durationMonths, boolean addOnOnly, Instant termEndsAt) {
    BigDecimal subtotal = sum(items.stream().map(line -> line.getUnitPrice()
        .multiply(BigDecimal.valueOf(line.getQuantity()))).toList());
    BigDecimal discountTotal = sum(items.stream().map(OrderLine::getDiscount).toList());
    BigDecimal walletCredit = Boolean.TRUE.equals(request.getApplyWalletCredit()) && StringUtils.hasText(shopId)
        ? applyWallet(items, walletService.available(shopId), planFloor)
        : BigDecimal.ZERO;
    return new PricedCart(plan, List.copyOf(items), subtotal, discountTotal, walletCredit,
        subtotal.subtract(discountTotal).subtract(walletCredit), durationMonths, addOnOnly, termEndsAt);
  }

  /**
   * Spends the balance line by line after vouchers (§24): the plan line first, then add-ons and OCR
   * scan packs in cart order (owner decision 2026-11-21: scan packs take credit like any add-on). The plan line is never taken below {@code planFloor}
   * (the plan's base price; null = no floor); credit it cannot take flows on to add-ons. Only reads
   * the balance; checkout reserves it.
   */
  static BigDecimal applyWallet(List<OrderLine> items, BigDecimal available, BigDecimal planFloor) {
    BigDecimal remaining = available == null ? BigDecimal.ZERO : available.max(BigDecimal.ZERO);
    BigDecimal applied = BigDecimal.ZERO;
    for (OrderLine line : items) {
      if (remaining.signum() <= 0) {
        continue;
      }
      BigDecimal take = remaining.min(walletRoom(line, planFloor));
      if (take.signum() > 0) {
        line.setWalletCredit(take);
        remaining = remaining.subtract(take);
        applied = applied.add(take);
      }
    }
    return applied;
  }

  /** How much wallet credit a line can take: all of it, except the plan line stops at its floor. */
  private static BigDecimal walletRoom(OrderLine line, BigDecimal planFloor) {
    if (planFloor == null || !PricingConstants.ITEM_TYPE_PLAN.equals(line.getType())) {
      return line.getLineTotal();
    }
    return line.getLineTotal().subtract(planFloor).max(BigDecimal.ZERO);
  }

  /**
   * One line per requested add-on, priced from the catalogue. Rejects unknown or hidden add-ons,
   * repeats, quantities the add-on does not allow, add-ons the plan already includes (§10), and
   * non-stackable add-ons in {@code held} (already held live by the shop).
   */
  private List<OrderLine> addOnLines(Plan plan, List<QuoteRequest.AddOnLine> requested, Set<String> held) {
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
      AddOnEligibility.ineligibleReason(plan, addOn, held).ifPresent(reason -> {
        throw new ValidationException(reason);
      });
      lines.add(addOnLine(addOn, quantity, PricingConstants.ITEM_SOURCE_MANUAL));
    });
    return lines;
  }

  /**
   * Applies each voucher to its add-on line (§10 Voucher UX): a voucher for an add-on not in the
   * cart adds a line of its own ({@code itemSource = VOUCHER}); one voucher per line. A plan voucher
   * applies to the plan line (r4.8 is reversed for them), capped so the plan never sells below its
   * base price; it is rejected in an add-on-only cart or for another plan. Rejections happen here,
   * during pricing, before checkout reserves anything.
   */
  private void applyVouchers(Plan plan, List<OrderLine> items, List<String> rawCodes, String shopId,
      Set<String> held) {
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
    Map<String, AddOnVoucher> vouchers = voucherService.requireUsableAll(codes, shopId);
    Map<String, AddOn> addOns = addOnCatalogue.byCode(vouchers.values().stream()
        .map(AddOnVoucher::getAddOnCode).filter(Objects::nonNull).collect(Collectors.toSet()));
    for (String code : codes) {
      AddOnVoucher voucher = vouchers.get(code);
      if (voucher.isPlanVoucher()) {
        applyPlanVoucher(plan, items, voucher, code);
        continue;
      }
      AddOn addOn = addOns.get(voucher.getAddOnCode());
      if (addOn == null || !addOn.isActive()) {
        throw notApplicable(code, "Voucher " + code + " is for an add-on that is no longer sold");
      }
      Optional<String> notForThisCart = AddOnEligibility.ineligibleReason(plan, addOn, held);
      if (notForThisCart.isPresent()) {
        throw notApplicable(code, notForThisCart.get());
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

  private void applyPlanVoucher(Plan plan, List<OrderLine> items, AddOnVoucher voucher, String code) {
    OrderLine line = items.stream().filter(item -> PricingConstants.ITEM_TYPE_PLAN.equals(item.getType()))
        .findFirst().orElse(null);
    if (line == null) {
      throw notApplicable(code, "Voucher " + code + " is for " + voucherPlanName(voucher)
          + "; add the plan to use it.");
    }
    if (!Objects.equals(voucher.getPlanCode(), line.getCode())) {
      throw notApplicable(code, "Voucher " + code + " is for " + voucherPlanName(voucher) + ", not "
          + line.getName() + ".");
    }
    if (line.getVoucherCode() != null) {
      throw notApplicable(code, line.getName() + " already has voucher " + line.getVoucherCode());
    }
    BigDecimal discount = PlanVoucherPricing.cappedDiscount(voucher.getType(), voucher.getValue(), plan);
    if (discount.signum() <= 0) {
      throw notApplicable(code, "Voucher " + code + " gives no discount on " + line.getName() + " right now.");
    }
    line.setDiscount(discount);
    line.setLineTotal(line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity())).subtract(discount));
    line.setVoucherCode(code);
  }

  private String voucherPlanName(AddOnVoucher voucher) {
    return planRepository.findByCode(voucher.getPlanCode()).map(Plan::getPlanName)
        .orElse(voucher.getPlanCode());
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
        .grantType(addOn.getGrantType())
        .grantsFeature(addOn.getGrantsFeature())
        .grantsQuantity(addOn.getGrantsQuantity())
        .billingType(addOn.getBillingType())
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

  /** SMS packs stay in the catalogue but cannot be bought until StockKart sends SMS. */
  /** Yearly price of a plan. Checkout charges exactly this, so a quote and its checkout agree. */
  public BigDecimal planPrice(Plan plan) {
    return PlanVoucherPricing.planPrice(plan);
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
        .addOnOnly(cart.addOnOnly())
        .termEndsAt(cart.termEndsAt())
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
        .walletCredit(line.getWalletCredit())
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
