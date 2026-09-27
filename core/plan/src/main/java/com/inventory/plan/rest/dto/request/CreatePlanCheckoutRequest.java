package com.inventory.plan.rest.dto.request;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Cart to buy. Same shape as {@link QuoteRequest} so a quote and its checkout price identically;
 * {@code planId} remains for legacy plans that have no catalogue code.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class CreatePlanCheckoutRequest extends QuoteRequest {

  private String planId;
}
