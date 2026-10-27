package com.inventory.pricing.api;

/** A rule that produced no rate for a batch, with the reason (e.g. {@code BELOW_COST}). */
public record SkippedRule(String name, String reason) {}
