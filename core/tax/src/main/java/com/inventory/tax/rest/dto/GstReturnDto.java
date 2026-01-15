package com.inventory.tax.rest.dto;

import com.inventory.tax.domain.model.GstReturnStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for GST Return entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GstReturnDto {
    
    private String id;
    
    private String shopId;
    
    private String returnType;
    
    private String period;
    
    private GstReturnStatus status;
    
    private BigDecimal totalTaxableValue;
    
    private BigDecimal totalCgst;
    
    private BigDecimal totalSgst;
    
    private BigDecimal totalIgst;
    
    private BigDecimal totalCess;
    
    private BigDecimal totalTaxLiability;
    
    private String filedBy;
    
    private Instant filedAt;
    
    private Instant createdAt;
    
    private Instant updatedAt;
}

