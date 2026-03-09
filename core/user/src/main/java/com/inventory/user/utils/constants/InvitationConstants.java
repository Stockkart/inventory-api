package com.inventory.user.utils.constants;

/**
 * Constants for invitation-related configurations.
 */
public final class InvitationConstants {

  /**
   * Invitation expiry duration in days.
   * Invitations expire 2 weeks (14 days) after creation.
   */
  public static final int INVITATION_EXPIRY_DAYS = 14;
  /**
   * Number of seconds in a day.
   */
  private static final int SECONDS_PER_DAY = 24 * 3600;
  /**
   * Invitation expiry duration in seconds.
   * Calculated as: INVITATION_EXPIRY_DAYS * SECONDS_PER_DAY
   */
  public static final long INVITATION_EXPIRY_SECONDS = INVITATION_EXPIRY_DAYS * SECONDS_PER_DAY;

  private InvitationConstants() {
    // Utility class - prevent instantiation
  }
}
