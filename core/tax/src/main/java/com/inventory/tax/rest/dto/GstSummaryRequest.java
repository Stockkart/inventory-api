package com.inventory.tax.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for fetching GST summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GstSummaryRequest {
    
    private String period; // Format: YYYY-MM (e.g., "2026-01")
    
    private String returnType; // GSTR1, GSTR3B (optional, defaults to all)
}

