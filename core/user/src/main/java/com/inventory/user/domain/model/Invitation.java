package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "invitations")
public class Invitation {

  @Id
  private String invitationId;
  private String shopId;
  private String shopName; // Denormalized shop name for easier access
  private String inviterUserId; // User who sent the invitation
  private String inviteeUserId; // User who is being invited
  private String inviteeEmail; // Email of the user being invited
  private String role; // Role to be assigned when accepted
  private String status; // PENDING, ACCEPTED, REJECTED, EXPIRED
  private Instant createdAt;
  private Instant expiresAt;
  private Instant acceptedAt;
  private Instant rejectedAt;
}

