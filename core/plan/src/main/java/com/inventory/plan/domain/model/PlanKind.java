package com.inventory.plan.domain.model;

/**
 * What a catalog row represents.
 *
 * <p>{@code GET /plans} returns subscription plans, add-ons and OCR top-ups in one list. Before this
 * existed, clients told them apart by matching {@code planName} against literals such as
 * "Extra User Plan", which broke whenever the catalog was renamed.
 */
public enum PlanKind {
  /** A subscription tier. */
  PLAN,
  /** An optional extra bought alongside a plan. */
  ADDON,
  /** A one-off pack of OCR invoice credits. */
  OCR_TOPUP
}
