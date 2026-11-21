package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveSystemDefault;
import com.inventory.hr.domain.repository.LeaveTypeRepository;
import com.inventory.hr.mapper.HrSettingsMapper;
import com.inventory.hr.rest.dto.request.SaveLeaveTypeRequest;
import com.inventory.hr.rest.dto.response.LeaveTypeResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.validation.LeaveTypeValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Leave types (R6.1). The three R6.2 defaults are seeded on the first read of a shop and only
 * when missing, so seeding is idempotent and a shop that renamed a default keeps its name.
 */
@Slf4j
@Service
public class LeaveTypeService {

  static final String ENTITY = "LEAVE_TYPE";

  @Autowired private LeaveTypeRepository leaveTypeRepository;

  @Autowired private LeaveTypeValidator validator;

  @Autowired private HrSettingsMapper mapper;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrAuditService auditService;

  public List<LeaveTypeResponse> list(String userId, String shopId) {
    guard.requireAny(userId, shopId);
    return mapper.toLeaveTypeResponses(ensureDefaults(shopId));
  }

  public LeaveTypeResponse create(String userId, String shopId, SaveLeaveTypeRequest request) {
    guard.require(userId, shopId, HrPermission.MANAGE_SETTINGS);
    validator.validate(request);
    // seed first, so a new "Casual" made before the first read clashes here, not in the seeder
    ensureDefaults(shopId);
    String name = request.getName().trim();
    if (leaveTypeRepository.existsByShopIdAndNameIgnoreCase(shopId, name)) {
      throw duplicate();
    }
    Instant now = Instant.now();
    LeaveType type = new LeaveType();
    type.setShopId(shopId);
    type.setName(name);
    type.setPaid(request.getPaid());
    type.setYearlyQuotaDays(request.getYearlyQuotaDays());
    type.setActive(request.getActive() != null ? request.getActive() : true);
    type.setCreatedAt(now);
    type.setUpdatedAt(now);
    type.setUpdatedByUserId(userId);
    LeaveType saved = save(type, shopId);
    audit(shopId, userId, saved, null);
    return mapper.toResponse(saved);
  }

  /** Full update of name, paid and quota; {@code active} only when sent. */
  public LeaveTypeResponse update(String userId, String shopId, String id,
      SaveLeaveTypeRequest request) {
    guard.require(userId, shopId, HrPermission.MANAGE_SETTINGS);
    validator.validate(request);
    LeaveType type = leaveTypeRepository.findByShopIdAndId(shopId, id)
        .orElseThrow(() -> new ResourceNotFoundException("Leave type", "id", id));
    String name = request.getName().trim();
    if (!name.equalsIgnoreCase(type.getName())
        && leaveTypeRepository.existsByShopIdAndNameIgnoreCase(shopId, name)) {
      throw duplicate();
    }
    Map<String, Object> before = snapshot(type);
    type.setName(name);
    type.setPaid(request.getPaid());
    type.setYearlyQuotaDays(request.getYearlyQuotaDays());
    if (request.getActive() != null) {
      type.setActive(request.getActive());
    }
    type.setUpdatedAt(Instant.now());
    type.setUpdatedByUserId(userId);
    LeaveType saved = save(type, shopId);
    audit(shopId, userId, saved, before);
    return mapper.toResponse(saved);
  }

  /**
   * The shop's types, inserting any missing default first. One read when nothing is missing.
   * A concurrent seed (or a user type already holding the name) hits the unique (shopId, name)
   * index; that default is then skipped, never duplicated.
   */
  List<LeaveType> ensureDefaults(String shopId) {
    List<LeaveType> types = leaveTypeRepository.findByShopIdOrderByCreatedAtAsc(shopId);
    Set<LeaveSystemDefault> missing = EnumSet.allOf(LeaveSystemDefault.class);
    types.forEach(t -> missing.remove(t.getSystemDefault()));
    if (missing.isEmpty()) {
      return types;
    }
    Instant now = Instant.now();
    boolean inserted = false;
    for (LeaveSystemDefault d : missing) {
      LeaveType seed = defaultType(shopId, d, now);
      // a shop type with the same name stands in for the default
      if (types.stream().anyMatch(t -> t.getName().equalsIgnoreCase(seed.getName()))) {
        continue;
      }
      try {
        leaveTypeRepository.insert(seed);
        inserted = true;
      } catch (DuplicateKeyException e) {
        log.debug("Leave type default {} already present for shop {}", d, shopId);
      }
    }
    return inserted ? leaveTypeRepository.findByShopIdOrderByCreatedAtAsc(shopId) : types;
  }

  /** R6.2: Casual 12 days paid, Sick 6 days paid, Unpaid leave with no limit. */
  static LeaveType defaultType(String shopId, LeaveSystemDefault d, Instant now) {
    LeaveType t = new LeaveType();
    t.setShopId(shopId);
    t.setSystemDefault(d);
    t.setActive(true);
    t.setCreatedAt(now);
    t.setUpdatedAt(now);
    switch (d) {
      case CASUAL -> {
        t.setName("Casual");
        t.setPaid(true);
        t.setYearlyQuotaDays(new BigDecimal("12"));
      }
      case SICK -> {
        t.setName("Sick");
        t.setPaid(true);
        t.setYearlyQuotaDays(new BigDecimal("6"));
      }
      case UNPAID -> {
        t.setName("Unpaid leave");
        t.setPaid(false);
        t.setYearlyQuotaDays(null);
      }
    }
    return t;
  }

  private LeaveType save(LeaveType type, String shopId) {
    try {
      return leaveTypeRepository.save(type);
    } catch (DuplicateKeyException e) {
      throw duplicate();
    } catch (DataAccessException e) {
      log.error("Database error while saving a leave type for shop {}: {}", shopId, e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving the leave type");
    }
  }

  private void audit(String shopId, String userId, LeaveType saved, Map<String, Object> before) {
    auditService.record(AuditEntry.of(shopId, userId, HrAuditAction.SETTINGS_CHANGED, ENTITY,
        saved.getId(), before, snapshot(saved)));
  }

  private static Map<String, Object> snapshot(LeaveType t) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", t.getName());
    m.put("paid", t.getPaid());
    m.put("yearlyQuotaDays", Objects.toString(t.getYearlyQuotaDays(), null));
    m.put("active", t.getActive());
    return m;
  }

  private static ResourceExistsException duplicate() {
    return new ResourceExistsException("A leave type with this name already exists");
  }
}
