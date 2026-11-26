package com.inventory.user.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One account's profile photo (worker-app R1.7, DD9): a JPEG or PNG of at most 512 × 512 pixels
 * and 300 KB, already shrunk by the phone. One document per account, keyed by the user id, so a new
 * upload replaces the old one. Served only through {@code GET /api/v1/users/{userId}/photo} behind
 * the Bearer chain and the who-may-see rule (R11.5); never on a public URL.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "user_photos")
public class UserPhoto {

  /** The account the photo belongs to ({@code UserAccount.userId}). */
  @Id
  private String userId;

  private byte[] bytes;

  /** {@code image/jpeg} or {@code image/png}, as detected from the bytes, not as declared. */
  private String contentType;

  private Instant updatedAt;
}
