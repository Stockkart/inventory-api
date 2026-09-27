package com.inventory.plan.service.referral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import com.inventory.plan.domain.model.ReferralReviewReason;
import com.inventory.plan.domain.repository.ReferralAttributionRepository;
import com.inventory.plan.mapper.ReferralMapper;
import com.inventory.plan.service.ShopProvider;
import com.inventory.plan.service.ShopProvider.ReferralShop;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ReferralAttributionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final ReferralShop REFERRER =
      new ReferralShop("referrer", "ABC Store", "SK-AB2CD3", "owner@abc.in", "+91 98765 43210");

  @Mock private ReferralAttributionRepository attributionRepository;
  @Mock private ShopProvider shopProvider;
  @Mock private UserShopMembershipService membershipService;
  @Spy private ReferralMapper referralMapper = new ReferralMapper() {};

  @InjectMocks
  private ReferralAttributionService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    lenient().when(attributionRepository.insert(any(ReferralAttribution.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(shopProvider.findByReferralCode("SK-AB2CD3")).thenReturn(Optional.of(REFERRER));
  }

  private void refereeContacts(String email, String phone) {
    when(shopProvider.getReferralShop("referee"))
        .thenReturn(Optional.of(new ReferralShop("referee", "New Shop", "SK-ZZZZZZ", email, phone)));
  }

  @Test
  void aCodeFromAnotherPartyResolves() {
    refereeContacts("new@shop.in", "9000000000");

    ReferralAttribution attribution = service.attribute("referee", "user-1", " sk-ab2cd3 ", "Ravi").orElseThrow();

    assertThat(attribution.getStatus()).isEqualTo(ReferralAttributionStatus.RESOLVED);
    assertThat(attribution.getReferrerShopId()).isEqualTo("referrer");
    assertThat(attribution.getReferrerCodeUsed()).isEqualTo("SK-AB2CD3");
    assertThat(attribution.getRawReferredByName()).isEqualTo("Ravi");
    assertThat(attribution.getResolvedAt()).isEqualTo(NOW);
  }

  @Test
  void aNameAloneWaitsForReview() {
    ReferralAttribution attribution = service.attribute("referee", "user-1", null, " Ravi ").orElseThrow();

    assertThat(attribution.getStatus()).isEqualTo(ReferralAttributionStatus.PENDING_REVIEW);
    assertThat(attribution.getReviewReason()).isEqualTo(ReferralReviewReason.NAME_ONLY);
    assertThat(attribution.getReferrerShopId()).isNull();
  }

  @Test
  void nothingIsRecordedWithoutAReferrer() {
    assertThat(service.attribute("referee", "user-1", " ", null)).isEmpty();
    verify(attributionRepository, never()).insert(any(ReferralAttribution.class));
  }

  @Test
  void theSameOwnerMatchingEmailOrPhoneGoesToReview() {
    when(membershipService.hasOwnerAccess("owner", "referrer")).thenReturn(true);
    assertThat(service.attribute("referee", "owner", "SK-AB2CD3", null).orElseThrow().getReviewReason())
        .isEqualTo(ReferralReviewReason.SAME_OWNER);

    refereeContacts(" Owner@ABC.in ", "9111111111");
    assertThat(service.attribute("referee", "user-1", "SK-AB2CD3", null).orElseThrow().getReviewReason())
        .isEqualTo(ReferralReviewReason.SAME_EMAIL);

    refereeContacts("other@shop.in", "098765-43210");
    ReferralAttribution byPhone = service.attribute("referee", "user-1", "SK-AB2CD3", null).orElseThrow();
    assertThat(byPhone.getStatus()).isEqualTo(ReferralAttributionStatus.PENDING_REVIEW);
    assertThat(byPhone.getReviewReason()).isEqualTo(ReferralReviewReason.SAME_PHONE);
  }

  @Test
  void aShopCannotReferItself() {
    when(shopProvider.findByReferralCode("SK-AB2CD3"))
        .thenReturn(Optional.of(new ReferralShop("referee", "Me", "SK-AB2CD3", null, null)));

    assertThat(service.attribute("referee", "user-1", "SK-AB2CD3", null).orElseThrow().getStatus())
        .isEqualTo(ReferralAttributionStatus.SELF_REFERRAL);
  }

  @Test
  void aSecondAttributionKeepsTheFirst() {
    ReferralAttribution first = ReferralAttribution.builder().refereeShopId("referee")
        .status(ReferralAttributionStatus.RESOLVED).build();
    when(attributionRepository.insert(any(ReferralAttribution.class))).thenThrow(new DuplicateKeyException("dup"));
    when(attributionRepository.findByRefereeShopId("referee")).thenReturn(Optional.of(first));

    assertThat(service.attribute("referee", "user-1", null, "Ravi")).containsSame(first);
  }

  @Test
  void registrationWithAnUnknownCodeIsRejected() {
    when(shopProvider.findByReferralCode("SK-NOPE22")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.checkReferral("sk-nope22", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("SK-NOPE22");
    assertThatThrownBy(() -> service.checkReferral(null, "x".repeat(121)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theCodeCheckRevealsOnlyValidityAndName() {
    when(shopProvider.findByReferralCode("SK-NOPE22")).thenReturn(Optional.empty());

    assertThat(service.check("sk-ab2cd3").getDisplayName()).isEqualTo("ABC Store");
    assertThat(service.check("sk-ab2cd3").isValid()).isTrue();
    assertThat(service.check("SK-NOPE22").isValid()).isFalse();
    assertThat(service.check("SK-NOPE22").getDisplayName()).isNull();
  }

  @Test
  void onlyAResolvedAttributionHasARewardableReferrer() {
    when(attributionRepository.findByRefereeShopId("referee")).thenReturn(Optional.of(ReferralAttribution.builder()
        .referrerShopId("referrer").status(ReferralAttributionStatus.PENDING_REVIEW).build()));
    assertThat(service.resolvedReferrer("referee")).isEmpty();

    when(attributionRepository.findByRefereeShopId("referee")).thenReturn(Optional.of(ReferralAttribution.builder()
        .referrerShopId("referrer").status(ReferralAttributionStatus.RESOLVED).build()));
    assertThat(service.resolvedReferrer("referee")).contains("referrer");
  }

  @Test
  void phonesCompareOnTheirLastTenDigits() {
    assertThat(ReferralAttributionService.normalisePhone("+91 98765-43210")).isEqualTo("9876543210");
    assertThat(ReferralAttributionService.normalisePhone("12345")).isNull();
  }
}
