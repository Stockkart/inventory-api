package com.inventory.user.service.api;

import com.inventory.user.api.PlatformAdmins;
import com.inventory.user.domain.model.PlatformRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PlatformAdminsImpl implements PlatformAdmins {

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Override
  public boolean isPlatformAdmin(String userId) {
    if (userId == null || userId.isBlank()) {
      return false;
    }
    return userAccountRepository.findById(userId)
        .map(account -> account.hasPlatformRole(PlatformRole.PLATFORM_ADMIN))
        .orElse(false);
  }
}
