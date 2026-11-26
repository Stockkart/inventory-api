package com.inventory.plan.domain.model;

import java.time.Instant;

/** A shop's live paid subscription term: the plan it is on and when that term ends. */
public record PaidTerm(String planId, Instant expiresAt) {}
