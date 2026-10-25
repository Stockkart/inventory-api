package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.HolidayRepository;
import com.inventory.hr.mapper.HrSettingsMapper;
import com.inventory.hr.rest.dto.request.SaveHolidayRequest;
import com.inventory.hr.rest.dto.response.HolidayResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.HolidayValidator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Shop holidays (R3); one per date per shop. Changes inside a locked month are rejected. */
@Slf4j
@Service
public class HolidayService {

  static final String ENTITY = "HOLIDAY";

  @Autowired private HolidayRepository holidayRepository;

  @Autowired private HolidayValidator validator;

  @Autowired private HrSettingsMapper mapper;

  @Autowired private HrPermissionGuard guard;

  @Autowired private PeriodLockService periodLockService;

  @Autowired private HrAuditService auditService;

  /** Holidays of a year (default: this year in Asia/Kolkata), by date. */
  public List<HolidayResponse> list(String userId, String shopId, Integer year) {
    guard.requireAny(userId, shopId);
    validator.validateYear(year);
    int y = year != null ? year : HrDateUtils.today().getYear();
    return mapper.toHolidayResponses(holidayRepository.findByShopIdAndYearOrderByDateAsc(shopId, y));
  }

  public HolidayResponse create(String userId, String shopId, SaveHolidayRequest request) {
    guard.require(userId, shopId, HrPermission.MANAGE_SETTINGS);
    validator.validate(request);
    periodLockService.assertOpen(shopId, HrDateUtils.periodOf(request.getDate()));
    if (holidayRepository.existsByShopIdAndDate(shopId, request.getDate())) {
      throw duplicate();
    }
    Holiday holiday = new Holiday();
    holiday.setShopId(shopId);
    apply(holiday, request, userId);
    holiday.setCreatedAt(holiday.getUpdatedAt());
    Holiday saved = save(holiday, shopId);
    audit(shopId, userId, saved, null, snapshot(saved));
    return mapper.toResponse(saved);
  }

  public HolidayResponse update(String userId, String shopId, String id,
      SaveHolidayRequest request) {
    guard.require(userId, shopId, HrPermission.MANAGE_SETTINGS);
    validator.validate(request);
    Holiday holiday = find(shopId, id);
    // both the old and the new month change, so both must be open
    periodLockService.assertOpen(shopId, HrDateUtils.periodOf(holiday.getDate()));
    periodLockService.assertOpen(shopId, HrDateUtils.periodOf(request.getDate()));
    if (!holiday.getDate().equals(request.getDate())
        && holidayRepository.existsByShopIdAndDate(shopId, request.getDate())) {
      throw duplicate();
    }
    Map<String, Object> before = snapshot(holiday);
    apply(holiday, request, userId);
    Holiday saved = save(holiday, shopId);
    audit(shopId, userId, saved, before, snapshot(saved));
    return mapper.toResponse(saved);
  }

  public void delete(String userId, String shopId, String id) {
    guard.require(userId, shopId, HrPermission.MANAGE_SETTINGS);
    Holiday holiday = find(shopId, id);
    periodLockService.assertOpen(shopId, HrDateUtils.periodOf(holiday.getDate()));
    holidayRepository.deleteByShopIdAndId(shopId, id);
    audit(shopId, userId, holiday, snapshot(holiday), null);
  }

  private Holiday find(String shopId, String id) {
    return holidayRepository.findByShopIdAndId(shopId, id)
        .orElseThrow(() -> new ResourceNotFoundException("Holiday", "id", id));
  }

  private static void apply(Holiday holiday, SaveHolidayRequest request, String userId) {
    holiday.setDate(request.getDate());
    holiday.setYear(HrDateUtils.parseDate(request.getDate()).getYear());
    holiday.setName(request.getName().trim());
    holiday.setUpdatedAt(Instant.now());
    holiday.setUpdatedByUserId(userId);
  }

  private Holiday save(Holiday holiday, String shopId) {
    try {
      return holidayRepository.save(holiday);
    } catch (DuplicateKeyException e) {
      // a concurrent create took the date between the check and the insert
      throw duplicate();
    } catch (DataAccessException e) {
      log.error("Database error while saving a holiday for shop {}: {}", shopId, e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving the holiday");
    }
  }

  private void audit(String shopId, String userId, Holiday holiday, Map<String, Object> before,
      Map<String, Object> after) {
    auditService.record(new AuditEntry(shopId, userId, HrAuditAction.SETTINGS_CHANGED, ENTITY,
        holiday.getId(), null, holiday.getDate(), HrDateUtils.periodOf(holiday.getDate()), before,
        after, null));
  }

  private static Map<String, Object> snapshot(Holiday holiday) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("date", holiday.getDate());
    m.put("name", holiday.getName());
    return m;
  }

  private static ResourceExistsException duplicate() {
    return new ResourceExistsException("This date is already a holiday");
  }
}
