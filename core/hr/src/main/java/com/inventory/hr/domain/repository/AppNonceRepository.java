package com.inventory.hr.domain.repository;

import com.inventory.hr.domain.model.AppNonce;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Seen request nonces ({@code hr_app_nonces}); unique {@code (pairingId, nonce)}, TTL {@code
 * seenAt} 900 s. The envelope check inserts and relies on the duplicate-key error to detect a
 * replay, so no finder is needed beyond the base interface.
 */
@Repository
public interface AppNonceRepository extends MongoRepository<AppNonce, String> {

  boolean existsByPairingIdAndNonce(String pairingId, String nonce);
}
