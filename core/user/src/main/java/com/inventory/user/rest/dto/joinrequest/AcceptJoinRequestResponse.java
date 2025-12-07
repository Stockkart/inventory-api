package com.inventory.user.rest.dto.joinrequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcceptJoinRequestResponse {
  private String requestId;
  private String shopId;
  private String shopName;
  private String userId;
  private String userEmail;
  private String userName;
  private Instant reviewedAt;
  private String message;
}

