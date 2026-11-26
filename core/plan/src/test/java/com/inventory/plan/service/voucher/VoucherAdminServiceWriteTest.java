package com.inventory.plan.service.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditService;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.VoucherType;
import com.inventory.plan.domain.repository.AddOnRepository;
import com.inventory.plan.domain.repository.AddOnVoucherRepository;
import com.inventory.plan.mapper.VoucherMapper;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.request.VoucherUpdateRequest;
import com.inventory.plan.validation.VoucherAdminValidator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
/** Update, batch and list behaviour of the voucher admin service. */
class VoucherAdminServiceWriteTest {

  @Mock private AddOnVoucherRepository voucherRepository;
  @Mock private AddOnRepository addOnRepository;
  @Mock private com.inventory.plan.domain.repository.PlanRepository planRepository;
  @Mock private MongoTemplate mongoTemplate;
  @Spy private VoucherMapper voucherMapper = new com.inventory.plan.mapper.VoucherMapperImpl();
  @Mock private AuditService auditService;
  @Spy private VoucherAdminValidator validator = new VoucherAdminValidator();
  @InjectMocks private VoucherAdminService service;

  @Test
  void anUpdateChangesOnlyTheFieldsItSends() {
    AddOnVoucher stored = AddOnVoucher.builder().id("v1").code("MKT-1").build();
    when(voucherRepository.findById("v1")).thenReturn(Optional.of(stored));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
        eq(AddOnVoucher.class))).thenReturn(stored);

    service.update("v1", VoucherUpdateRequest.builder().note("extended").build(), "admin-1");

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class),
        eq(AddOnVoucher.class));
    String set = update.getValue().getUpdateObject().get("$set").toString();
    assertThat(set).contains("note").doesNotContain("validTo").doesNotContain("maxRedemptions");
  }

  @Test
  void aBatchThatFailsPartWayLeavesNoVouchersBehind() {
    when(addOnRepository.findByCode("MARKETING_MODULE"))
        .thenReturn(Optional.of(AddOn.builder().code("MARKETING_MODULE").active(true).build()));
    AtomicInteger inserts = new AtomicInteger();
    when(mongoTemplate.insert(any(AddOnVoucher.class))).thenAnswer(inv -> {
      if (inserts.incrementAndGet() > 2) {
        throw new DuplicateKeyException("code");
      }
      return inv.getArgument(0);
    });

    VoucherGenerateRequest request = VoucherGenerateRequest.builder()
        .addOnCode("MARKETING_MODULE").type(VoucherType.FREE_ADDON).count(3).build();
    assertThatThrownBy(() -> service.generate(request, "admin-1")).isInstanceOf(BaseException.class);

    verify(mongoTemplate).remove(any(Query.class), eq(AddOnVoucher.class));
    verify(auditService, never()).record(any());
  }

  @Test
  void generatedCodesHaveATenCharacterRandomPart() {
    when(addOnRepository.findByCode("MARKETING_MODULE"))
        .thenReturn(Optional.of(AddOn.builder().code("MARKETING_MODULE").active(true).build()));
    when(mongoTemplate.insert(any(AddOnVoucher.class))).thenAnswer(inv -> inv.getArgument(0));
    ArgumentCaptor<AddOnVoucher> saved = ArgumentCaptor.forClass(AddOnVoucher.class);

    service.generate(VoucherGenerateRequest.builder().addOnCode("MARKETING_MODULE").type(VoucherType.FREE_ADDON)
        .prefix("DIWALI").build(), "admin-1");

    verify(mongoTemplate).insert(saved.capture());
    assertThat(saved.getValue().getCode()).matches("DIWALI-[A-Z2-9]{10}");
  }

  @Test
  void theListIsReadOnePageAtATime() {
    when(voucherRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

    service.list(null, null, null, 2, 50);

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(voucherRepository).findAll(page.capture());
    assertThat(page.getValue().getPageNumber()).isEqualTo(2);
    assertThat(page.getValue().getPageSize()).isEqualTo(50);
    assertThatThrownBy(() -> service.list(null, null, null, 0, 1000)).isInstanceOf(ValidationException.class);
  }

  @Test
  void pageDefaultsAreTheFirstHundred() {
    when(voucherRepository.findByAddOnCode(eq("MARKETING_MODULE"), any(Pageable.class))).thenReturn(List.of());

    service.list("marketing_module", null, null, null, null);

    verify(voucherRepository).findByAddOnCode("MARKETING_MODULE", PageRequest.of(0, 100,
        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
  }
}
