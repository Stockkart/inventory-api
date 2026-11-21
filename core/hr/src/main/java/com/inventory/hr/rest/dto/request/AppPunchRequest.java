package com.inventory.hr.rest.dto.request;

import lombok.Data;
import lombok.ToString;

/**
 * {@code POST /api/v1/hr/attendance-app/punches}: the ten fields of the {@code SKAT-PUNCH-1}
 * canonical payload (design "Canonical payloads"), in payload order, plus the signature the
 * biometric-gated punch key made over them (R5.6). Field names match {@code AppPunchRequest} in
 * {@code attendance-protocol/src/types.ts}.
 *
 * <p>The server rebuilds the payload from these fields and verifies the signature against the
 * pairing's stored public key; the shop, employee and pairing it then trusts are the pairing's,
 * never these (R5.7). Any direction the phone might send is not a field here: the server decides
 * it (R4.9).
 *
 * <p>Privacy (R9.2): there is no field for coordinates. {@code distanceMetres} is worked out on
 * the phone from the shop's saved location.
 */
@Data
public class AppPunchRequest {
  /** 24 lowercase hex characters; must be the pairing the envelope was signed by. */
  private String pairingId;
  /** 24 lowercase hex characters. */
  private String shopId;
  /** 24 lowercase hex characters. */
  private String employeeId;
  /** UUID made by the app, lowercase with hyphens; one per punch (R5.6). */
  private String punchId;
  /** {@code yyyy-MM-ddTHH:mm:ssZ}, UTC, whole seconds: the phone's clock at the tap. */
  private String phoneTime;
  /** true = saved on the phone while the server was not reachable (R6). */
  private Boolean offline;
  /** true = the shop's Location check was on as the app last received it. */
  private Boolean locationChecked;
  /** Whole metres from the shop; null when no location was obtained or the check was off. */
  private Integer distanceMetres;
  /** Whole metres, the phone's reported accuracy; null when none. */
  private Integer accuracyMetres;
  /** true = the phone marked the reading as a mock (faked) location (R4.6). */
  private Boolean mock;
  /** Base64 DER {@code ECDSA-Sig-Value} over the canonical punch payload. */
  @ToString.Exclude private String signature;
}
