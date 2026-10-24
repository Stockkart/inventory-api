package com.inventory.pricing.domain.model;

import java.util.List;

/** The rates generated for a batch, plus the rules skipped with a reason. */
public record RateGenerationResult(List<Rate> rates, List<SkippedRateRule> skipped) {}
