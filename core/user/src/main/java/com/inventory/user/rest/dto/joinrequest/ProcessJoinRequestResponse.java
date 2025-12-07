package com.inventory.user.rest.dto.joinrequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProcessJoinRequestResponse {
  private String requestId;
  private String shopId;
  private String shopName;
  private String userId;
  private String userEmail;
  private String userName;
  private String status; // APPROVED or REJECTED
  private Instant reviewedAt;
  private String message;
}

