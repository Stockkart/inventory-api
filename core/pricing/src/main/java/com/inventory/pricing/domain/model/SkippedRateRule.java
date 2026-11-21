package com.inventory.pricing.domain.model;

/** A rule that produced no rate for a batch, with the reason (e.g. {@code BELOW_COST}). */
public record SkippedRateRule(String name, String reason) {}
