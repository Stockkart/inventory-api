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
}

