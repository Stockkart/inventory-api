package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class AcceptInviteRequest {
  private String inviteToken;
  private String password;
}
