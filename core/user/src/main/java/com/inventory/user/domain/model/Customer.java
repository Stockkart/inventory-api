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
@Document(collection = "customers")
public class Customer {

  @Id
  private String id;
  private String name;
  private String phone;
  private String address;
  private String email;
  private String gstin; // Optional: GSTIN number
  private String dlNo; // Optional: D.L No.
  private String pan; // Optional: PAN number
  /** Optional link to UserAccount when customer is a registered user. */
  private String userId;
  private Instant createdAt;
  private Instant updatedAt;
}

