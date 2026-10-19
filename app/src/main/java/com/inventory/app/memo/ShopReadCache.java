package com.inventory.app.memo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shop reads ({@code shopRepository.findById}) remembered across requests for
 * {@code shop.cache.ttl-seconds} (60 s), so the plan-expiry check every request makes stops going
 * to MongoDB.
 *
 * <ul>
 *   <li>Holds pristine copies; {@link RequestReadMemoAspect} hands every caller its own copy.
 *   <li>Any save, insert or delete through {@code shopRepository} on this instance clears it, so a
 *       settings edit, plan change or new shop is seen by the next request.
 *   <li>Writes made elsewhere (another instance, a script, the database directly) are seen when
 *       the entry expires. Listed in the engineering handbook's IN_MEMORY_STATE.md.
 * </ul>
 */
@Slf4j
@Component
public class ShopReadCache {

  private final boolean enabled;
  private final Cache<String, Optional<Object>> shops;

  public ShopReadCache(
      @Value("${shop.cache.enabled:true}") boolean enabled,
      @Value("${shop.cache.ttl-seconds:60}") long ttlSeconds,
      @Value("${shop.cache.max-entries:10000}") long maxEntries) {
    this.enabled = enabled && ttlSeconds > 0;
    this.shops = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofSeconds(Math.max(ttlSeconds, 1)))
        .maximumSize(maxEntries)
        .build();
    log.info("Shop read cache {} (ttl {} s, max {} entries)", this.enabled ? "on" : "off", ttlSeconds, maxEntries);
  }

  /** The remembered result for this read key (may be an empty Optional); null when not cached. */
  Optional<Object> get(String key) {
    return enabled ? shops.getIfPresent(key) : null;
  }

  /** Taken before a database read; a write in between makes {@link #put} skip the stale result. */
  long generation() {
    return generation.get();
  }

  void put(String key, Optional<Object> pristine, long readStartedAt) {
    if (enabled && generation.get() == readStartedAt) {
      shops.put(key, pristine);
    }
  }

  /** Called after any write through shopRepository. */
  void clear() {
    generation.incrementAndGet();
    shops.invalidateAll();
  }

  private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
}
