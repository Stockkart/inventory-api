package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Meta;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Worker join requests ({@code hr_join_requests}). Indexes: {@code (shopId, status, requestedAt
 * desc)}; unique partial {@code (userId)} where {@code status in [PENDING, APPROVING]}; {@code
 * (userId, decidedAt desc)}; TTL {@code expiresAt}.
 *
 * <p>Status transitions ({@code PENDING → APPROVING}, {@code PENDING → REJECTED | WITHDRAWN}) are
 * conditional updates done with MongoTemplate in the service, not through this interface, so two
 * approvers deciding at once cannot both win.
 */
@Repository
public interface WorkerJoinRequestRepository extends MongoRepository<WorkerJoinRequest, String> {

  /** Path ids are read only scoped to the shop (tenancy), as every HR repository does. */
  @Meta(maxExecutionTimeMs = 3_000)
  Optional<WorkerJoinRequest> findByShopIdAndId(String shopId, String id);

  /** The inbox tab, newest first; the caller caps {@code size} at 200 (R4.1–R4.2, R11.3). */
  @Meta(maxExecutionTimeMs = 3_000)
  Page<WorkerJoinRequest> findByShopIdAndStatusOrderByRequestedAtDesc(String shopId,
      WorkerJoinStatus status, Pageable pageable);

  /** The "Join requests (N)" tab count (R4.1). */
  @Meta(maxExecutionTimeMs = 3_000)
  long countByShopIdAndStatus(String shopId, WorkerJoinStatus status);

  /**
   * The account's one open request, if any: call with {@code [PENDING, APPROVING]} (R3.5, R3.10,
   * R6.1). At most one exists by the partial unique index.
   */
  Optional<WorkerJoinRequest> findFirstByUserIdAndStatusIn(String userId,
      Collection<WorkerJoinStatus> statuses);

  /** The account's most recent decision after {@code since}; the Join screen's card (R4.12). */
  Optional<WorkerJoinRequest> findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(String userId,
      Instant since);

  /**
   * Rows stuck in a status since before {@code before}: the {@code APPROVING} sweep calls it with
   * {@code (APPROVING, now − 5 min)} and resets them to {@code PENDING} (design "Data Models").
   */
  @Meta(maxExecutionTimeMs = 3_000)
  List<WorkerJoinRequest> findByStatusAndUpdatedAtBefore(WorkerJoinStatus status, Instant before);

  /**
   * Every request of an account in the given statuses: the photo who-may-see rule asks with {@code
   * [PENDING, REJECTED]} to learn which shops' approvers may view the account's photo (R11.5).
   */
  @Meta(maxExecutionTimeMs = 3_000)
  List<WorkerJoinRequest> findByUserIdAndStatusIn(String userId,
      Collection<WorkerJoinStatus> statuses);
}
