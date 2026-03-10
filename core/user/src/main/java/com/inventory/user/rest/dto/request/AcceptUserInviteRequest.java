package com.inventory.user.rest.dto.request;

import lombok.Data;

@Data
public class AcceptUserInviteRequest {
  private String inviteId;
  private String password;
}
