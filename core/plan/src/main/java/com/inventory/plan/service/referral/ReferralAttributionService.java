package com.inventory.plan.service.referral;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import com.inventory.plan.domain.model.ReferralReviewReason;
import com.inventory.plan.domain.repository.ReferralAttributionRepository;
import com.inventory.plan.mapper.ReferralMapper;
import com.inventory.plan.rest.dto.response.ReferralCodeCheckResponse;
import com.inventory.plan.rest.dto.response.ReferralSummaryResponse;
import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.ShopProvider.ReferralShop;
import com.inventory.plan.utils.ReferralCodes;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Records who referred a shop, once, when the shop is registered (§11). A code resolves to a shop
 * automatically; a name only, or a referrer that looks like the same owner, waits for review and never
 * earns a reward on its own.
 */
@Slf4j
@Service
public class ReferralAttributionService {

  static final int MAX_NAME_LENGTH = 120;
  private static final int PHONE_DIGITS = 10;

  @Autowired
  private ReferralAttributionRepository attributionRepository;

  @Autowired
  private ShopProvider shopProvider;

  @Autowired
  @Lazy
  private UserShopMembershipService membershipService;

  @Autowired
  private ReferralMapper referralMapper;

  Clock clock = Clock.systemUTC();

  /** Rejects a registration that names a code no shop owns, before the shop is created. */
  public void checkReferral(String rawCode, String rawName) {
    if (StringUtils.hasText(rawName) && rawName.trim().length() > MAX_NAME_LENGTH) {
      throw new ValidationException("Referrer name must be at most " + MAX_NAME_LENGTH + " characters");
    }
    if (StringUtils.hasText(rawCode) && shopProvider.findByReferralCode(ReferralCodes.normalise(rawCode)).isEmpty()) {
      throw new ValidationException("Referral code " + ReferralCodes.normalise(rawCode) + " does not exist");
    }
  }

  /**
   * Attribution for a newly registered shop, or empty when no referrer was given. A second call for
   * the same shop returns the first attribution unchanged.
   */
  public Optional<ReferralAttribution> attribute(
      String refereeShopId, String registeringUserId, String rawCode, String rawName) {
    boolean hasCode = StringUtils.hasText(rawCode);
    if (!hasCode && !StringUtils.hasText(rawName)) {
      return Optional.empty();
    }
    ReferralAttribution attribution = ReferralAttribution.builder()
        .refereeShopId(refereeShopId)
        .referrerCodeUsed(hasCode ? ReferralCodes.normalise(rawCode) : null)
        .rawReferredByName(StringUtils.hasText(rawName) ? rawName.trim() : null)
        .createdAt(clock.instant())
        .build();
    if (hasCode) {
      resolve(attribution, registeringUserId);
    } else {
      review(attribution, ReferralReviewReason.NAME_ONLY);
    }
    try {
      return Optional.of(attributionRepository.insert(attribution));
    } catch (DuplicateKeyException e) {
      log.info("Shop {} already has a referral attribution; keeping the first", refereeShopId);
      return attributionRepository.findByRefereeShopId(refereeShopId);
    }
  }

  private void resolve(ReferralAttribution attribution, String registeringUserId) {
    Optional<ReferralShop> referrer = shopProvider.findByReferralCode(attribution.getReferrerCodeUsed());
    if (referrer.isEmpty()) {
      review(attribution, ReferralReviewReason.UNKNOWN_CODE);
      return;
    }
    ReferralShop referrerShop = referrer.get();
    attribution.setReferrerShopId(referrerShop.shopId());
    if (referrerShop.shopId().equals(attribution.getRefereeShopId())) {
      attribution.setStatus(ReferralAttributionStatus.SELF_REFERRAL);
      return;
    }
    Optional<ReferralReviewReason> sameParty = samePartySignal(attribution.getRefereeShopId(), referrerShop, registeringUserId);
    if (sameParty.isPresent()) {
      review(attribution, sameParty.get());
      return;
    }
    attribution.setStatus(ReferralAttributionStatus.RESOLVED);
    attribution.setResolvedAt(attribution.getCreatedAt());
  }

  /**
   * An owner opening a second branch is plausible, so matching owner, email or phone goes to review
   * rather than being rejected; it is never credited automatically (§11 anti-abuse).
   */
  private Optional<ReferralReviewReason> samePartySignal(String refereeShopId, ReferralShop referrer, String userId) {
    if (StringUtils.hasText(userId) && membershipService.hasOwnerAccess(userId, referrer.shopId())) {
      return Optional.of(ReferralReviewReason.SAME_OWNER);
    }
    Optional<ReferralShop> referee = shopProvider.getReferralShop(refereeShopId);
    if (referee.isEmpty()) {
      return Optional.empty();
    }
    String refereeEmail = normaliseEmail(referee.get().contactEmail());
    if (refereeEmail != null && refereeEmail.equals(normaliseEmail(referrer.contactEmail()))) {
      return Optional.of(ReferralReviewReason.SAME_EMAIL);
    }
    String refereePhone = normalisePhone(referee.get().contactPhone());
    if (refereePhone != null && refereePhone.equals(normalisePhone(referrer.contactPhone()))) {
      return Optional.of(ReferralReviewReason.SAME_PHONE);
    }
    return Optional.empty();
  }

  private static void review(ReferralAttribution attribution, ReferralReviewReason reason) {
    attribution.setStatus(ReferralAttributionStatus.PENDING_REVIEW);
    attribution.setReviewReason(reason);
  }

  static String normaliseEmail(String email) {
    return StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
  }

  /** Last ten digits, so {@code +91 98765-43210} and {@code 09876543210} match. */
  static String normalisePhone(String phone) {
    if (!StringUtils.hasText(phone)) {
      return null;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < PHONE_DIGITS) {
      return null;
    }
    return digits.substring(digits.length() - PHONE_DIGITS);
  }

  /** Signup screen check. Says only whether the code exists and the shop's display name. */
  public ReferralCodeCheckResponse check(String rawCode) {
    String code = ReferralCodes.normalise(rawCode);
    Optional<ReferralShop> referrer = code.isEmpty() ? Optional.empty() : shopProvider.findByReferralCode(code);
    return referralMapper.toCheckResponse(referrer.isPresent(), referrer.map(ReferralShop::name).orElse(null));
  }

  public ReferralSummaryResponse summary(String shopId) {
    String code = shopProvider.getReferralShop(shopId).map(ReferralShop::referralCode).orElse(null);
    long resolved = attributionRepository.countByReferrerShopIdAndStatus(shopId, ReferralAttributionStatus.RESOLVED);
    long pending = attributionRepository.countByReferrerShopIdAndStatus(shopId, ReferralAttributionStatus.PENDING_REVIEW);
    ReferralAttribution referredBy = attributionRepository.findByRefereeShopId(shopId).orElse(null);
    return referralMapper.toSummary(code, resolved, pending, referredBy);
  }

  /** The resolved referrer of a shop, if any. Only a RESOLVED attribution can earn a reward. */
  public Optional<String> resolvedReferrer(String refereeShopId) {
    return attributionRepository.findByRefereeShopId(refereeShopId)
        .filter(a -> a.getStatus() == ReferralAttributionStatus.RESOLVED)
        .map(ReferralAttribution::getReferrerShopId)
        .filter(Objects::nonNull);
  }
}
