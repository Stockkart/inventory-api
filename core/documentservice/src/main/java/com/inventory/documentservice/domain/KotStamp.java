package com.inventory.documentservice.domain;

/** The single switch behind all three KOT print paths: normal, reprint and cancellation. */
public enum KotStamp {
  NONE(""),
  REPRINT("REPRINT"),

  /** Some lines cancelled; the rest of the ticket still stands. Lists only the cancelled lines. */
  PARTIAL_CANCELLATION("PARTIAL CANCELLATION"),

  /** The whole ticket is dead. */
  CANCELLED("CANCELLED");

  private final String label;

  KotStamp(String label) {
    this.label = label;
  }

  /** What the kitchen reads. Never the enum name — an underscore on paper is a leak, not a word. */
  public String getLabel() {
    return label;
  }
}
