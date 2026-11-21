package com.inventory.hr.service;

import com.inventory.user.service.UserShopMembershipService;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Login names for "who did it" columns (attendance history, audit rows), through the user
 * module's service (L5), one batched read per response. Names are only for display: a failed
 * lookup returns no names rather than failing the screen.
 */
@Slf4j
@Service
public class HrUserNames {

  @Autowired private UserShopMembershipService membershipService;

  /** Names by user id; ids without a name are left out. */
  public Map<String, String> namesOf(Collection<String> userIds) {
    Set<String> ids = new TreeSet<>();
    userIds.stream().filter(Objects::nonNull).forEach(ids::add);
    if (ids.isEmpty()) {
      return Map.of();
    }
    try {
      return membershipService.namesOf(ids);
    } catch (RuntimeException e) {
      log.warn("[hr-names] name lookup failed: {}", e.getClass().getSimpleName());
      return Map.of();
    }
  }
}
