package com.inventory.pricing.api;

import java.util.List;

/** The rates a shop's rules generate for a batch, and the rules skipped with a reason. */
public record GeneratedRates(List<GeneratedRate> rates, List<SkippedRule> skipped) {}
