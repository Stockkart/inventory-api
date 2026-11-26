package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * One-time pairing codes ({@code hr_pairing_codes}). Indexes: unique {@code codeHash}; {@code
 * (shopId, employeeId, status)}; TTL {@code expiresAt}. Codes are looked up by hash only; the
 * plaintext never reaches the database.
 */
@Repository
public interface PairingCodeRepository extends MongoRepository<PairingCode, String> {

  /** The code a phone typed or scanned, by its SHA-256 hash (claim, R2.5). */
  Optional<PairingCode> findByCodeHash(String codeHash);

  /** The code a phone claimed, by the hash of its claim token (complete / abandon). */
  Optional<PairingCode> findByClaimTokenHash(String claimTokenHash);

  /** The employee's codes in one status; used to cancel the previous {@code UNUSED} one (R2.4). */
  List<PairingCode> findByShopIdAndEmployeeIdAndStatus(String shopId, String employeeId,
      PairingCodeStatus status);
}
