package com.inventory.hr.rest.dto.response;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code POST /api/v1/hr/employees/{id}/pairing-codes} and {@code POST /api/v1/hr/me/pairing-codes}:
 * a fresh one-time pairing code (R2.3). This is the only place the plaintext code exists besides
 * the manager's screen; the server keeps its hash.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PairingCodeResponse {
  /** 8 characters from the pairing-code alphabet. */
  private String code;
  /** {@code https://stockkart.in/attendance/pair#<CODE>}, what the QR encodes. */
  private String qrPayload;
  /** 15 minutes after creation. */
  private Instant expiresAt;
}
