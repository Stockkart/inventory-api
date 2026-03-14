package com.inventory.notifications.adapter;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Result of a send attempt via a channel adapter.
 */
@Getter
@AllArgsConstructor
public class SendResult {
  private final boolean success;
  private final String externalId;
  private final String errorMessage;

  public static SendResult success(String externalId) {
    return new SendResult(true, externalId, null);
  }

  public static SendResult failure(String errorMessage) {
    return new SendResult(false, null, errorMessage);
  }
}
