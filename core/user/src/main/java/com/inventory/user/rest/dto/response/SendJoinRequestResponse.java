package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendJoinRequestResponse {
  private String requestId;
  private String shopId;
  private String shopName;
  private String status;
  private String message;
  private Instant createdAt;
}
