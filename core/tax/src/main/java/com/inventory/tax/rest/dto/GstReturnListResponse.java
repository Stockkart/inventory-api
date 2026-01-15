package com.inventory.tax.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for listing GST returns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GstReturnListResponse {
    
    private List<GstReturnDto> returns;
    
    private int page;
    
    private int limit;
    
    private long total;
    
    private int totalPages;
}

