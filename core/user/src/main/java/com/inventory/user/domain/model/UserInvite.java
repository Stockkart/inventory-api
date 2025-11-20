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
@Document(collection = "user_invites")
public class UserInvite {

    @Id
    private String inviteId;
    private String shopId;
    private String name;
    private String email;
    private String role;
    private String token;
    private Instant expiresAt;
    private boolean accepted;
}

