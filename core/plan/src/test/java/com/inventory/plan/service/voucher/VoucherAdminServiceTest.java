package com.inventory.plan.service.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.VoucherTarget;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.mapper.VoucherMapperImpl;
import com.inventory.plan.rest.dto.request.PlanVoucherPreviewRequest;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.response.AdminVoucherResponse;
import com.inventory.plan.rest.dto.response.PlanVoucherPreviewResponse;
import com.inventory.plan.validation.VoucherAdminValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class VoucherAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
  private static final String BELOW_BASE =
      "₹4,000 off takes Starter to ₹3,999, below its base price ₹4,999. Most you can give: ₹3,000 (37.50%).";

  @Mock private AddOnVoucherRepository voucherRepository;
  @Mock private AddOnRepository addOnRepository;
  @Mock private PlanRepository planRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Mock private VoucherAdminValidator validator;
  @Mock private AuditService auditService;
  @Spy private VoucherMapper voucherMapper = new VoucherMapperImpl();

  @InjectMocks
  private VoucherAdminService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void generatesPlanVoucherWithPlanPrefixAndAudit() {
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starter("7999", "4999")));
    echoInserts();

    List<AdminVoucherResponse> created = service.generate(planRequest("STARTER", "1000", 2), "admin-1");

    assertThat(created).hasSize(2);
    assertThat(created).allSatisfy(v -> {
      assertThat(v.getTarget()).isEqualTo(VoucherTarget.PLAN);
      assertThat(v.getPlanCode()).isEqualTo("STARTER");
      assertThat(v.getPlanName()).isEqualTo("Starter");
      assertThat(v.getAddOnCode()).isNull();
      assertThat(v.getCode()).startsWith("STA-");
    });
    Map<String, Object> after = auditAfter();
    assertThat(after).containsEntry("target", VoucherTarget.PLAN).containsEntry("planCode", "STARTER");
    assertThat(after).doesNotContainKey("addOnCode");
  }

  @Test
  void rejectsPlanVoucherBelowBase() {
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starter("7999", "4999")));

    assertThatThrownBy(() -> service.generate(planRequest("STARTER", "4000", 1), "admin-1"))
        .isInstanceOf(ValidationException.class)
        .hasMessage(BELOW_BASE);
    verify(mongoTemplate, never()).insert(any(AddOnVoucher.class));
  }

  @Test
  void rejectsUnknownOrHiddenOrUnsoldPlan() {
    when(planRepository.findByCode("NOPE")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.generate(planRequest("NOPE", "1000", 1), "admin-1"))
        .isInstanceOf(ResourceNotFoundException.class);

    Plan hidden = starter("7999", "4999");
    hidden.setCode("HIDDEN");
    hidden.setActive(false);
    when(planRepository.findByCode("HIDDEN")).thenReturn(Optional.of(hidden));
    assertThatThrownBy(() -> service.generate(planRequest("HIDDEN", "1000", 1), "admin-1"))
        .isInstanceOf(ValidationException.class);

    Plan unsold = starter("7999", "4999");
    unsold.setCode("UNSOLD");
    unsold.setArcPrice(null);
    unsold.setPrice(null);
    when(planRepository.findByCode("UNSOLD")).thenReturn(Optional.of(unsold));
    assertThatThrownBy(() -> service.generate(planRequest("UNSOLD", "1000", 1), "admin-1"))
        .isInstanceOf(ValidationException.class);

    verify(mongoTemplate, never()).insert(any(AddOnVoucher.class));
  }

  @Test
  void addOnVoucherGenerationUnchanged() {
    AddOn addOn = new AddOn();
    addOn.setCode("EXTRA_SMS");
    addOn.setActive(true);
    when(addOnRepository.findByCode("EXTRA_SMS")).thenReturn(Optional.of(addOn));
    echoInserts();
    VoucherGenerateRequest request = VoucherGenerateRequest.builder()
        .addOnCode("extra_sms").type(VoucherType.PERCENT_OFF).value(new BigDecimal("10")).count(1).build();

    List<AdminVoucherResponse> created = service.generate(request, "admin-1");

    assertThat(created.get(0).getAddOnCode()).isEqualTo("EXTRA_SMS");
    assertThat(created.get(0).getTarget()).isEqualTo(VoucherTarget.ADD_ON);
    assertThat(created.get(0).getPlanCode()).isNull();
    assertThat(created.get(0).getCode()).startsWith("EXT-");
    Map<String, Object> after = auditAfter();
    assertThat(after).containsEntry("addOnCode", "EXTRA_SMS").doesNotContainKey("planCode").doesNotContainKey("target");
    assertThat(after.keySet()).containsExactly("addOnCode", "type", "value", "count", "maxRedemptions",
        "singleUsePerShop", "issuedToShopId", "validFrom", "validTo", "codes");
  }

  @Test
  void previewReturnsFiguresAndMessage() {
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starter("7999", "4999")));

    PlanVoucherPreviewResponse over = service.previewPlan(preview("4000"));
    assertThat(over.isAllowed()).isFalse();
    assertThat(over.getMaxFlat()).isEqualByComparingTo("3000.00");
    assertThat(over.getMaxPercent()).isEqualByComparingTo("37.50");
    assertThat(over.getMessage()).isEqualTo(BELOW_BASE);
    assertThat(over.getPlanCode()).isEqualTo("STARTER");
    assertThat(over.getPlanName()).isEqualTo("Starter");

    PlanVoucherPreviewResponse ok = service.previewPlan(preview("1000"));
    assertThat(ok.isAllowed()).isTrue();
    assertThat(ok.getPriceAfter()).isEqualByComparingTo("6999.00");
    assertThat(ok.getMessage()).isNull();
  }

  @Test
  void previewOfUnknownOrUnsoldPlanFailsLikeGenerate() {
    when(planRepository.findByCode("NOPE")).thenReturn(Optional.empty());
    PlanVoucherPreviewRequest request = preview("1000");
    request.setPlanCode("nope");
    assertThatThrownBy(() -> service.previewPlan(request)).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void listFiltersByPlanCode() {
    AddOnVoucher voucher = AddOnVoucher.builder().code("STA-1").target(VoucherTarget.PLAN).planCode("STARTER").build();
    when(voucherRepository.findByPlanCode(eq("STARTER"), any(Sort.class))).thenReturn(List.of(voucher));
    when(planRepository.findAll()).thenReturn(List.of(starter("7999", "4999")));

    List<AdminVoucherResponse> result = service.list(null, " starter ", null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getPlanName()).isEqualTo("Starter");
    verify(voucherRepository, never()).findAll(any(Sort.class));
  }

  @Test
  void listByAddOnTargetIncludesLegacyNullTargetVouchers() {
    AddOnVoucher legacy = AddOnVoucher.builder().code("OLD-1").addOnCode("EXTRA_SMS").build();
    when(voucherRepository.findByTargetNot(eq(VoucherTarget.PLAN), any(Sort.class))).thenReturn(List.of(legacy));

    List<AdminVoucherResponse> result = service.list(null, null, VoucherTarget.ADD_ON);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getTarget()).isEqualTo(VoucherTarget.ADD_ON);
  }

  @Test
  void listByPlanTargetAndNoFilter() {
    when(voucherRepository.findByTarget(eq(VoucherTarget.PLAN), any(Sort.class))).thenReturn(List.of());
    when(voucherRepository.findAll(any(Sort.class))).thenReturn(List.of());

    assertThat(service.list(null, null, VoucherTarget.PLAN)).isEmpty();
    assertThat(service.list(null, null, null)).isEmpty();
  }

  @Test
  void listToleratesLegacyPlansWithoutCode() {
    Plan legacyA = new Plan();
    legacyA.setPlanName("Old A");
    Plan legacyB = new Plan();
    legacyB.setPlanName("Old B");
    when(planRepository.findAll()).thenReturn(List.of(legacyA, legacyB, starter("7999", "4999")));
    AddOnVoucher voucher = AddOnVoucher.builder().code("STA-1").target(VoucherTarget.PLAN).planCode("STARTER").build();
    when(voucherRepository.findAll(any(Sort.class))).thenReturn(List.of(voucher));

    assertThat(service.list(null, null, null).get(0).getPlanName()).isEqualTo("Starter");
  }

  private void echoInserts() {
    when(mongoTemplate.insert(any(AddOnVoucher.class))).thenAnswer(inv -> {
      AddOnVoucher v = inv.getArgument(0);
      AddOnVoucher copy = AddOnVoucher.builder().build();
      org.springframework.beans.BeanUtils.copyProperties(v, copy);
      copy.setId("id-" + v.getCode());
      return copy;
    });
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> auditAfter() {
    ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(captor.capture());
    return (Map<String, Object>) captor.getValue().getAfter();
  }

  private static VoucherGenerateRequest planRequest(String planCode, String flat, int count) {
    return VoucherGenerateRequest.builder()
        .target(VoucherTarget.PLAN).planCode(planCode).type(VoucherType.FLAT_OFF)
        .value(new BigDecimal(flat)).count(count).build();
  }

  private static PlanVoucherPreviewRequest preview(String flat) {
    return PlanVoucherPreviewRequest.builder().planCode("STARTER").type(VoucherType.FLAT_OFF)
        .value(new BigDecimal(flat)).build();
  }

  private static Plan starter(String arcPrice, String basePrice) {
    Plan plan = new Plan();
    plan.setCode("STARTER");
    plan.setPlanName("Starter");
    plan.setArcPrice(new BigDecimal(arcPrice));
    plan.setBasePrice(new BigDecimal(basePrice));
    return plan;
  }
}
