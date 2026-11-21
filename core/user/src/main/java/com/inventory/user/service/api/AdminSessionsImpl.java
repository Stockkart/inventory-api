package com.inventory.user.service.api;

import com.inventory.user.api.AdminSessions;
import com.inventory.user.service.AdminAuthService;
import com.inventory.user.service.AdminAuthentication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdminSessionsImpl implements AdminSessions {

  @Autowired
  private AdminAuthService adminAuthService;

  @Override
  public AdminPrincipal authenticate(String token) {
    AdminAuthentication auth = adminAuthService.authenticate(token);
    return new AdminPrincipal(auth.adminId(), auth.admin().isMustChangePassword());
  }
}
