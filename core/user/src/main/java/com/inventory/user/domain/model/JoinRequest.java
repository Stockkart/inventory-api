package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "join_requests")
public class JoinRequest {

  @Id
  private String requestId;
  private String shopId;
  private String shopName; // Denormalized shop name for easier access
  private String userId; // User requesting to join
  private String userEmail; // Email of the user requesting
  private String userName; // Name of the user requesting
  private String requestedRole; // Role requested by the user (OWNER, ADMIN, MANAGER, CASHIER)
  private JoinRequestStatus status; // PENDING, APPROVED, REJECTED
  private String message; // Optional message from user
  private Instant createdAt;
  private Instant reviewedAt;
  private String reviewedBy; // User ID who reviewed the request
}

