package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.util.GstMath;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.RateGenerationResult;
import com.inventory.pricing.domain.model.RateRuleSpec;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.PricingUtils;
import com.inventory.pricing.utils.RateRuleCalculator;
import com.inventory.pricing.utils.RateTermsText;
import com.inventory.product.domain.model.RateRule;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.ShopRateRulesDocument;
import com.inventory.product.domain.model.enums.ShopType;
import com.inventory.product.domain.repository.ShopRateRulesRepository;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.mapper.RateRulesMapper;
import com.inventory.product.rest.dto.request.PreviewRateRulesRequest;
import com.inventory.product.rest.dto.request.UpdateRateRulesRequest;
import com.inventory.product.rest.dto.response.RateRuleDto;
import com.inventory.product.rest.dto.response.RateRulesPreviewResponse;
import com.inventory.product.rest.dto.response.RateRulesResponse;
import com.inventory.product.utils.constants.ProductMetricsConstants;
import com.inventory.product.validation.RateRulesValidator;
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.service.RbacService;
import com.inventory.user.service.UserShopMembershipService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Shop-level rate rules: read, replace, and preview what they would generate. */
@Service
@Slf4j
public class RateRulesService {

  private static final String CAFE_VERTICAL = "cafe";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private final ShopRateRulesRepository rulesRepository;
  private final ShopRepository shopRepository;
  private final UserShopMembershipService membershipService;
  private final ShopValidator shopValidator;
  private final RbacService rbacService;
  private final RateRulesValidator validator;
  private final RateRulesMapper mapper;
  private final MetricsWrapper metrics;

  public RateRulesService(
      ShopRateRulesRepository rulesRepository,
      ShopRepository shopRepository,
      UserShopMembershipService membershipService,
      ShopValidator shopValidator,
      RbacService rbacService,
      RateRulesValidator validator,
      RateRulesMapper mapper,
      MetricsWrapper metrics) {
    this.rulesRepository = rulesRepository;
    this.shopRepository = shopRepository;
    this.membershipService = membershipService;
    this.shopValidator = shopValidator;
    this.rbacService = rbacService;
    this.validator = validator;
    this.mapper = mapper;
    this.metrics = metrics;
  }

  public RateRulesResponse getRules(String shopId, String userId) {
    requireShopId(shopId);
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    Shop shop = loadShop(shopId);
    return toResponse(shop, savedRules(shopId), userId);
  }

  public RateRulesResponse updateRules(String shopId, String userId, UpdateRateRulesRequest request) {
    requireShopId(shopId);
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    if (!rbacService.canEditRates(userId, shopId)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED, "You do not have permission to edit rates");
    }
    Shop shop = loadShop(shopId);
    if (isCafe(shop)) {
      throw new BaseException(ErrorCode.RATE_RULE_INVALID,
          "Rate rules are not supported for this business type");
    }
    List<RateRuleDto> incoming = request == null || request.getRules() == null
        ? List.of() : request.getRules();
    validator.validate(incoming, shop.getShopType());
    try {
      Instant now = Instant.now();
      ShopRateRulesDocument doc = rulesRepository.findByShopId(shopId).orElseGet(() -> {
        ShopRateRulesDocument created = new ShopRateRulesDocument();
        created.setShopId(shopId);
        created.setCreatedAt(now);
        return created;
      });
      List<RateRule> rules = mapper.toEntities(incoming);
      rules.forEach(r -> r.setName(r.getName().trim()));
      doc.setRules(rules);
      doc.setUpdatedAt(now);
      doc.setUpdatedByUserId(userId);
      ShopRateRulesDocument saved = rulesRepository.save(doc);
      metrics.record(ProductMetricsConstants.RATE_RULES_UPDATES, 1,
          "module", ProductMetricsConstants.MODULE, "operation", "update");
      return toResponse(shop, saved.getRules(), userId);
    } catch (DataAccessException e) {
      log.error("Failed to save rate rules for shop {}: {}", shopId, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to save rate rules", e);
    }
  }

  public RateRulesPreviewResponse preview(String shopId, String userId, PreviewRateRulesRequest request) {
    requireShopId(shopId);
    shopValidator.validateShopAccess(membershipService.hasAccess(userId, shopId));
    Shop shop = loadShop(shopId);
    PreviewRateRulesRequest in = request == null ? new PreviewRateRulesRequest() : request;

    List<RateRule> rules;
    if (in.getRules() != null) {
      validator.validate(in.getRules(), shop.getShopType());
      rules = mapper.toEntities(in.getRules());
    } else {
      rules = savedRules(shopId);
    }
    List<RateRuleSpec> specs = isCafe(shop) ? List.of() : rules.stream().map(mapper::toSpec).toList();

    BigDecimal selling = in.getSellingPrice();
    BigDecimal ptr = in.getPriceToRetail();
    BigDecimal mrp = in.getMaximumRetailPrice();
    if (shop.getShopType() == ShopType.RETAILER) {
      // A retailer enters one selling price; MRP and PTR mirror it on save.
      ptr = selling;
      mrp = selling;
    }
    Scheme purchaseScheme = mapper.toScheme(in.getPurchaseScheme());
    BigDecimal cost = PricingUtils.computeEffectiveCostPrice(in.getCostPrice(),
        in.getPurchaseAdditionalDiscount(), purchaseScheme,
        Boolean.TRUE.equals(in.getCostPriceIncludesTax()), in.getSgst(), in.getCgst());
    BigDecimal gst = GstMath.parseGstRate(in.getSgst()).add(GstMath.parseGstRate(in.getCgst()));

    RateGenerationResult generated = RateRuleCalculator.generate(specs, selling, ptr, mrp, cost, gst);
    List<RateRulesPreviewResponse.PreviewRate> rates = new ArrayList<>();
    for (Rate rate : generated.rates()) {
      rates.add(RateRulesPreviewResponse.PreviewRate.builder()
          .name(rate.getName())
          .base(rate.getBase())
          .baseValue(rate.getBaseValue())
          .afterDiscount(afterDiscount(rate.getBaseValue(), rate.getDiscountPercent()))
          .price(rate.getPrice())
          .discountPercent(rate.getDiscountPercent())
          .scheme(mapper.toSchemeDto(rate.getScheme()))
          .gstMode(rate.getGstMode())
          .termsText(RateTermsText.of(rate))
          .build());
    }
    List<RateRulesPreviewResponse.SkippedRateRuleDto> skipped = generated.skipped().stream()
        .map(s -> new RateRulesPreviewResponse.SkippedRateRuleDto(s.name(), s.reason()))
        .toList();
    return new RateRulesPreviewResponse(rates, skipped);
  }

  private static BigDecimal afterDiscount(BigDecimal baseValue, BigDecimal discountPercent) {
    if (baseValue == null) {
      return null;
    }
    BigDecimal after = baseValue;
    if (discountPercent != null && discountPercent.signum() > 0) {
      after = baseValue.multiply(BigDecimal.ONE.subtract(
          discountPercent.divide(HUNDRED, 4, RoundingMode.HALF_UP)));
    }
    return after.setScale(2, RoundingMode.HALF_UP);
  }

  private RateRulesResponse toResponse(Shop shop, List<RateRule> rules, String userId) {
    return RateRulesResponse.builder()
        .enabled(!isCafe(shop))
        .baseOptions(RateRulesValidator.baseOptions(shop.getShopType()))
        .rules(mapper.toDtos(rules))
        .canEdit(rbacService.canEditRates(userId, shop.getShopId()))
        .build();
  }

  private List<RateRule> savedRules(String shopId) {
    return rulesRepository.findByShopId(shopId)
        .map(ShopRateRulesDocument::getRules)
        .orElseGet(List::of);
  }

  private Shop loadShop(String shopId) {
    return shopRepository.findById(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "shopId", shopId));
  }

  private static boolean isCafe(Shop shop) {
    return CAFE_VERTICAL.equalsIgnoreCase(shop.getVerticalId());
  }

  private static void requireShopId(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
  }
}
