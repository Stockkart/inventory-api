package com.inventory.user.rest.dto.user;

import lombok.Data;

@Data
public class AcceptUserInviteRequest {
  private String inviteId;
  private String password;
}

