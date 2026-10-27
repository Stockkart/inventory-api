package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.LeaveSystemDefault;
import com.inventory.hr.domain.repository.LeaveTypeRepository;
import com.inventory.hr.mapper.HrSettingsMapper;
import com.inventory.hr.mapper.HrSettingsMapperImpl;
import com.inventory.hr.rest.dto.request.SaveLeaveTypeRequest;
import com.inventory.hr.rest.dto.response.LeaveTypeResponse;
import com.inventory.hr.validation.LeaveTypeValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class LeaveTypeServiceTest {

  @Mock private LeaveTypeRepository leaveTypeRepository;
  @Spy private LeaveTypeValidator validator = new LeaveTypeValidator();
  @Spy private HrSettingsMapper mapper = new HrSettingsMapperImpl();
  @Mock private HrPermissionGuard guard;
  @Mock private HrAuditService auditService;
  @InjectMocks private LeaveTypeService service;

  private static List<LeaveType> defaults() {
    List<LeaveType> types = new ArrayList<>();
    for (LeaveSystemDefault d : LeaveSystemDefault.values()) {
      types.add(LeaveTypeService.defaultType(SHOP, d, Instant.EPOCH));
    }
    return types;
  }

  @Test
  void theFirstReadSeedsTheThreeDefaults() {
    when(leaveTypeRepository.findByShopIdOrderByCreatedAtAsc(SHOP))
        .thenReturn(List.of(), defaults());

    List<LeaveTypeResponse> types = service.list(USER, SHOP);

    ArgumentCaptor<LeaveType> inserted = ArgumentCaptor.forClass(LeaveType.class);
    verify(leaveTypeRepository, times(3)).insert(inserted.capture());
    LeaveType casual = inserted.getAllValues().stream()
        .filter(t -> t.getSystemDefault() == LeaveSystemDefault.CASUAL).findFirst().orElseThrow();
    LeaveType sick = inserted.getAllValues().stream()
        .filter(t -> t.getSystemDefault() == LeaveSystemDefault.SICK).findFirst().orElseThrow();
    LeaveType unpaid = inserted.getAllValues().stream()
        .filter(t -> t.getSystemDefault() == LeaveSystemDefault.UNPAID).findFirst().orElseThrow();
    assertEquals(new BigDecimal("12"), casual.getYearlyQuotaDays());
    assertEquals(true, casual.getPaid());
    assertEquals(new BigDecimal("6"), sick.getYearlyQuotaDays());
    assertEquals(false, unpaid.getPaid());
    assertNull(unpaid.getYearlyQuotaDays());
    assertEquals(3, types.size());
  }

  @Test
  void laterReadsDoNotSeedAgain() {
    when(leaveTypeRepository.findByShopIdOrderByCreatedAtAsc(SHOP)).thenReturn(defaults());

    service.list(USER, SHOP);

    verify(leaveTypeRepository, never()).insert(any(LeaveType.class));
  }

  @Test
  void aConcurrentSeedIsToleratedAndAShopNamedTypeStandsIn() {
    LeaveType ownCasual = new LeaveType();
    ownCasual.setShopId(SHOP);
    ownCasual.setName("casual");
    when(leaveTypeRepository.findByShopIdOrderByCreatedAtAsc(SHOP))
        .thenReturn(new ArrayList<>(List.of(ownCasual)), defaults());
    when(leaveTypeRepository.insert(any(LeaveType.class)))
        .thenThrow(new DuplicateKeyException("raced"))
        .thenAnswer(i -> i.getArgument(0));

    service.list(USER, SHOP);

    // Casual skipped (shop has its own), Sick raced, Unpaid inserted
    verify(leaveTypeRepository, times(2)).insert(any(LeaveType.class));
  }

  @Test
  void aDuplicateNameIs409() {
    when(leaveTypeRepository.findByShopIdOrderByCreatedAtAsc(SHOP)).thenReturn(defaults());
    when(leaveTypeRepository.existsByShopIdAndNameIgnoreCase(SHOP, "Sick")).thenReturn(true);
    SaveLeaveTypeRequest request = new SaveLeaveTypeRequest();
    request.setName("Sick");
    request.setPaid(true);

    assertThrows(ResourceExistsException.class, () -> service.create(USER, SHOP, request));
  }

  @Test
  void aQuotaMustBeWholeOrHalfDays() {
    SaveLeaveTypeRequest request = new SaveLeaveTypeRequest();
    request.setName("Festival");
    request.setPaid(true);
    request.setYearlyQuotaDays(new BigDecimal("2.3"));

    assertThrows(ValidationException.class, () -> service.create(USER, SHOP, request));
  }
}
