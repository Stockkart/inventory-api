package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

  public QuoteResponse quote(QuoteRequest request) {
    if (request == null || !StringUtils.hasText(request.getPlanCode())) {
      throw new ValidationException("planCode is required");
    }
    int durationMonths = resolveDuration(request.getDurationMonths());
    if (!CollectionUtils.isEmpty(request.getAddOns())) {
      throw new ValidationException("Add-ons are not available yet");
    }
    if (!CollectionUtils.isEmpty(request.getVoucherCodes())) {
      throw new ValidationException("Vouchers are not available yet");
    }

    String planCode = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
    Plan plan = planRepository.findByCode(planCode)
        .filter(EffectivePlanResolver::isActive)
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", planCode));

    BigDecimal unitPrice = planPrice(plan);
    if (unitPrice == null || unitPrice.signum() <= 0) {
      throw new ValidationException("Plan " + plan.getCode() + " is not for sale");
    }
    QuoteResponse.QuoteItem planLine = QuoteResponse.QuoteItem.builder()
        .type(PricingConstants.ITEM_TYPE_PLAN)
        .code(plan.getCode())
        .name(plan.getPlanName())
        .quantity(1)
        .unitPrice(unitPrice)
        .discount(BigDecimal.ZERO)
        .lineTotal(unitPrice)
        .itemSource(PricingConstants.ITEM_SOURCE_MANUAL)
        .build();

    List<QuoteResponse.QuoteItem> items = List.of(planLine);
    BigDecimal subtotal = sum(items.stream().map(QuoteResponse.QuoteItem::getLineTotal).toList());
    BigDecimal discountTotal = sum(items.stream().map(QuoteResponse.QuoteItem::getDiscount).toList());
    // No wallet exists yet; applyWalletCredit is accepted so the request shape is final.
    BigDecimal walletCredit = BigDecimal.ZERO;

    Instant now = Instant.now();
    return QuoteResponse.builder()
        .items(items)
        .subtotal(subtotal)
        .discountTotal(discountTotal)
        .walletCredit(walletCredit)
        .taxInclusive(true)
        .grandTotal(subtotal.subtract(discountTotal).subtract(walletCredit))
        .currency(PlanPaymentConstants.CURRENCY_INR)
        .durationMonths(durationMonths)
        .pricingVersion(PricingConstants.PRICING_VERSION)
        .quotedAt(now)
        .expiresAt(now.plus(PricingConstants.QUOTE_TTL))
        .build();
  }

  /** Yearly price of a plan. Checkout charges exactly this, so a quote and its checkout agree. */
  public BigDecimal planPrice(Plan plan) {
    return plan.getArcPrice() != null ? plan.getArcPrice() : plan.getPrice();
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
