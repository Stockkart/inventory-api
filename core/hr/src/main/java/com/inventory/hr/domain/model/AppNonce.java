package com.inventory.hr.domain.model;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A request-envelope nonce the server has already seen, so a captured request cannot be replayed
 * (design "Canonical payloads"). Unique {@code (pairingId, nonce)}; the TTL index on {@code seenAt}
 * removes rows after 15 minutes. Holds no shop, employee or key data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "hr_app_nonces")
public class AppNonce {

  @Id private String id;
  private String pairingId;
  /** UUID v4 from the {@code X-Request-Nonce} header. */
  private String nonce;
  /** TTL 900 s. */
  private Instant seenAt;
}
