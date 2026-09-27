package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.domain.model.EntitlementSource;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.domain.model.ShopEntitlements;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.mapper.AddOnMapper;
import com.inventory.plan.rest.dto.request.AddOnGrantRequest;
import com.inventory.plan.validation.AddOnAdminValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class AddOnAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final Instant TERM_END = Instant.parse("2026-10-01T10:00:00Z");

  @Mock private AddOnRepository addOnRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private AddOnMapper addOnMapper;
  @Mock private AddOnAdminValidator validator;
  @Mock private AuditService auditService;
  @Mock private EntitlementService entitlementService;
  @Mock private ShopAddOnService shopAddOnService;

  @InjectMocks
  private AddOnAdminService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void manualGrantOfAnAnnualAddOnEndsWithTheShopsCurrentTermAndIsAudited() {
    AddOn marketing = feature();
    when(addOnRepository.findByCode("MARKETING_MODULE")).thenReturn(Optional.of(marketing));
    when(entitlementService.resolve("shop-1")).thenReturn(new ShopEntitlements("shop-1", null, null,
        EntitlementSource.TRIAL, Set.of(), 2, 100, TERM_END, Set.of()));
    ShopAddOn granted = ShopAddOn.builder().id("g-1").shopId("shop-1").addOnCode("MARKETING_MODULE").quantity(1).build();
    when(shopAddOnService.grantByAdmin("shop-1", marketing, 1, TERM_END, "admin-1", "goodwill")).thenReturn(granted);

    service.grant(request("marketing_module"), "admin-1");

    ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(audit.capture());
    assertThat(audit.getValue().getAction()).isEqualTo("ADD_ON_GRANTED");
    assertThat(audit.getValue().getReason()).isEqualTo("goodwill");
    assertThat(audit.getValue().getTargetId()).isEqualTo("g-1");
  }

  @Test
  void manualGrantWithoutATermNeedsAnExplicitExpiry() {
    when(addOnRepository.findByCode("MARKETING_MODULE")).thenReturn(Optional.of(feature()));
    when(entitlementService.resolve("shop-1")).thenReturn(new ShopEntitlements("shop-1", null, null,
        EntitlementSource.TRIAL, Set.of(), 2, 100, NOW.minusSeconds(1), Set.of()));

    assertThatThrownBy(() -> service.grant(request("MARKETING_MODULE"), "admin-1"))
        .isInstanceOf(ValidationException.class);
    verify(shopAddOnService, never()).grantByAdmin(any(), any(), anyInt(), any(), any(), any());
  }

  @Test
  void creditsNeverExpire() {
    AddOn credits = AddOn.builder().id("a-2").code("OCR_TOPUP_500").billingType(AddOnBillingType.ONE_TIME)
        .grantType(AddOnGrantType.OCR_CREDITS).grantsQuantity(500).build();
    when(addOnRepository.findByCode("OCR_TOPUP_500")).thenReturn(Optional.of(credits));
    when(shopAddOnService.grantByAdmin(eq("shop-1"), eq(credits), eq(1), eq(null), eq("admin-1"), eq("goodwill")))
        .thenReturn(ShopAddOn.builder().id("g-2").shopId("shop-1").build());

    service.grant(request("OCR_TOPUP_500"), "admin-1");

    verify(entitlementService, never()).resolve(any());
  }

  private static AddOnGrantRequest request(String code) {
    return AddOnGrantRequest.builder().shopId("shop-1").addOnCode(code).reason("goodwill").build();
  }

  private static AddOn feature() {
    return AddOn.builder().id("a-1").code("MARKETING_MODULE").billingType(AddOnBillingType.ANNUAL)
        .grantType(AddOnGrantType.FEATURE).grantsFeature(PlanFeature.MARKETING).grantsQuantity(1).build();
  }
}
