package com.inventory.user.rest.dto.auth;

import lombok.Data;

@Data
public class AcceptInviteRequest {
  private String inviteToken;
  private String password;
}

