package com.inventory.notifications.domain.model;

/**
 * Email template identifiers. Maps to Resend template IDs or inline content.
 * Add more templates as needed (e.g. password reset, order confirmation).
 */
public enum EmailTemplate {
  /** Welcome email after signup */
  WELCOME_SIGNUP,
  /** Login notification (e.g. new login alert) */
  LOGIN_NOTIFICATION,
  /** Password reset link email */
  FORGOT_PASSWORD
}
