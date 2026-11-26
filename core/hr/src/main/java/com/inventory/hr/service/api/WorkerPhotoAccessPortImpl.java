package com.inventory.hr.service.api;

import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.service.HrAccessService;
import com.inventory.user.api.port.WorkerPhotoAccessPort;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link WorkerPhotoAccessPort} for the user module's photo endpoint (worker-app R11.5, Property
 * 25): an approver may see the photo of an account that has a join or link request in their shop
 * that is Pending, or Rejected within the last {@value #REJECTED_VISIBLE_DAYS} days. "Approver"
 * means holding {@code ADD_EDIT_EMPLOYEES} there, the same gate as the inbox. HR sits above user
 * and implements user's port here (module-boundaries B6).
 */
@Slf4j
@Component
public class WorkerPhotoAccessPortImpl implements WorkerPhotoAccessPort {

  /** How long after a rejection the approver may still see the photo (R4.12, R11.3). */
  static final int REJECTED_VISIBLE_DAYS = 90;

  private final WorkerJoinRequestRepository joinRequestRepository;
  private final HrAccessService hrAccessService;

  public WorkerPhotoAccessPortImpl(WorkerJoinRequestRepository joinRequestRepository,
      HrAccessService hrAccessService) {
    this.joinRequestRepository = joinRequestRepository;
    this.hrAccessService = hrAccessService;
  }

  @Override
  public boolean mayViewRequestPhoto(String viewerUserId, String ownerUserId) {
    if (!StringUtils.hasText(viewerUserId) || !StringUtils.hasText(ownerUserId)) {
      return false;
    }
    Instant cutoff = Instant.now().minus(Duration.ofDays(REJECTED_VISIBLE_DAYS));
    Set<String> shops = new LinkedHashSet<>();
    for (WorkerJoinRequest r : joinRequestRepository.findByUserIdAndStatusIn(ownerUserId,
        EnumSet.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.REJECTED))) {
      if (r.getShopId() == null) {
        continue;
      }
      boolean visible = r.getStatus() == WorkerJoinStatus.PENDING
          || (r.getDecidedAt() != null && !r.getDecidedAt().isBefore(cutoff));
      if (visible) {
        shops.add(r.getShopId());
      }
    }
    for (String shopId : shops) {
      try {
        if (hrAccessService.permissions(viewerUserId, shopId)
            .contains(HrPermission.ADD_EDIT_EMPLOYEES)) {
          return true;
        }
      } catch (RuntimeException e) {
        // a viewer with no access to the shop resolves to no permissions; nothing to grant
        log.debug("[hr-photo] access of {} in {} not resolved: {}", viewerUserId, shopId,
            e.getClass().getSimpleName());
      }
    }
    return false;
  }
}
