package com.inventory.user.rest.dto.request;

import com.inventory.user.rest.dto.response.JoinRequestAction;
import lombok.Data;

@Data
public class AcceptRejectJoinRequestRequest {
  private JoinRequestAction action;
}
