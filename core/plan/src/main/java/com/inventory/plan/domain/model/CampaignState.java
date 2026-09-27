package com.inventory.plan.domain.model;

/** Derived on the server from the campaign dates; never stored. */
public enum CampaignState {
  UPCOMING,
  STARTING_SOON,
  LIVE,
  ENDING_SOON;

  public boolean isLive() {
    return this == LIVE || this == ENDING_SOON;
  }
}
