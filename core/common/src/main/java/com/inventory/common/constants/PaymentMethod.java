package com.inventory.common.constants;

import java.util.Locale;
import java.util.Optional;

/**
 * How a transaction was tendered.
 *
 * <p>Stored on documents as a free-text string, so this is a parsing layer rather than a column
 * type — {@link #parse(String)} returns empty for anything unrecognised and callers keep their own
 * fallback. That preserves the historical behaviour where an unknown method still round-trips as
 * the original string on responses.
 *
 * <p>The three {@code *_*} constants are split tenders: the amount is divided across two legs.
 */
public enum PaymentMethod {
  CASH,
  ONLINE,
  UPI,
  BANK,
  CARD,
  CREDIT,
  /** Half cash, half online. */
  CASH_ONLINE,
  /** Half online, remainder on credit. */
  ONLINE_CREDIT,
  /** Half cash, remainder on credit. */
  CREDIT_CASH;

  /** Recognised method, or empty when blank or unknown. */
  public static Optional<PaymentMethod> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Recognised method, falling back to {@code fallback} when blank or unknown. */
  public static PaymentMethod from(String raw, PaymentMethod fallback) {
    return parse(raw).orElse(fallback);
  }

  /** Normalised uppercase token, or the trimmed original when unrecognised. */
  public static String normalise(String raw, PaymentMethod fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback.name();
    }
    return raw.trim().toUpperCase(Locale.ROOT);
  }

  /** Settles to the online bucket rather than cash. */
  public boolean isOnlineTender() {
    return this == ONLINE || this == UPI || this == BANK || this == CARD;
  }

  /** Splits the amount across two legs rather than landing wholly in one. */
  public boolean isSplitTender() {
    return this == CASH_ONLINE || this == ONLINE_CREDIT || this == CREDIT_CASH;
  }
}
