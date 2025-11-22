package com.inventory.user.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class UserAccount {

  @Id
  private String userId;
  private String name;
  private String role;
  private String shopId;
  private String email;
  private String password;
  private boolean active;
  private boolean inviteAccepted;
  private Instant updatedAt;
  private List<UserToken> tokens;

  public List<UserToken> getTokens() {
    if (tokens == null) {
      tokens = new ArrayList<>();
    }
    return tokens;
  }
}

