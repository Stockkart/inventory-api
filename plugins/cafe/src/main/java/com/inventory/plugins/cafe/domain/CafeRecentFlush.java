package com.inventory.plugins.cafe.domain;

import lombok.Data;

/**
 * One entry in {@link CafeTab#getRecentFlushKeys()}: an idempotency key this tab has already
 * claimed under, and the flush it produced.
 *
 * <p>{@link CafePendingFlush} remembers only the <i>latest</i> key, which is not enough. A client
 * parks its key in {@code sessionStorage} so it survives a remount, so a key two flushes old can
 * still arrive — and by then {@code pendingFlush} carries somebody else's key, the tab has been
 * composed again, and the claim's {@code $ne} compares against the wrong record and matches. The
 * stale request would then claim a round it never asked for, append it to whatever bill it was
 * carrying, and send it to the kitchen, all returned to a caller that believes it is looking at
 * its original tickets.
 *
 * <p>Keeping the {@code flushId} alongside the key is what lets a stale retry be a genuine no-op
 * that returns the tickets that key created, rather than an error. The list is bounded — see
 * {@code CafeTabFlusher.RECENT_FLUSH_KEYS_KEPT} — because a tab is long-lived and this is a
 * memory of recent attempts, not an audit log.
 */
@Data
public class CafeRecentFlush {

  private String idempotencyKey;
  private String flushId;
}
