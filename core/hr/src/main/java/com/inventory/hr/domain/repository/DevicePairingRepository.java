package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.enums.PairingStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Attendance app pairings ({@code hr_device_pairings}). Indexes: unique partial {@code (shopId,
 * employeeId)} and partial {@code (installationId)} where {@code status = ACTIVE}; {@code (shopId,
 * employeeId, status)}; partial {@code (scheduledUnpairAt)} where ACTIVE; TTL {@code purgeAt}.
 */
@Repository
public interface DevicePairingRepository extends MongoRepository<DevicePairing, String> {

  /** The pairing the phone claims, only when it has the given status (envelope check, R5.7). */
  Optional<DevicePairing> findByIdAndStatus(String id, PairingStatus status);

  /** The employee's pairing with the given status; at most one when {@code ACTIVE} (D3). */
  Optional<DevicePairing> findByShopIdAndEmployeeIdAndStatus(String shopId, String employeeId,
      PairingStatus status);

  /** Pairings of several employees with the given status (register, employee list, R8.12). */
  @Meta(maxExecutionTimeMs = 3000)
  List<DevicePairing> findByShopIdAndEmployeeIdInAndStatus(String shopId,
      Collection<String> employeeIds, PairingStatus status);

  /** The installation's pairing with the given status; at most one when {@code ACTIVE} (R2.11). */
  Optional<DevicePairing> findByInstallationIdAndStatus(String installationId,
      PairingStatus status);

  /** Pairings due for the exit-driven unpair ({@code PairingExitJob}, R3.4). */
  @Meta(maxExecutionTimeMs = 3000)
  List<DevicePairing> findByStatusAndScheduledUnpairAtLessThanEqual(PairingStatus status,
      Instant now);

  /** All pairings of the shop with the given status (R1.9 count, after an exited filter). */
  @Meta(maxExecutionTimeMs = 3000)
  List<DevicePairing> findByShopIdAndStatus(String shopId, PairingStatus status);
}
