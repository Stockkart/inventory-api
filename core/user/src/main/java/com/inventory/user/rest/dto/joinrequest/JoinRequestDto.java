package com.inventory.user.rest.dto.joinrequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequestDto {
  private String requestId;
  private String shopId;
  private String shopName;
  private String userId;
  private String userEmail;
  private String userName;
  private String requestedRole;
  private String status;
  private String message;
  private Instant createdAt;
  private Instant reviewedAt;
  private String reviewedBy;
}

