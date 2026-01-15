package com.inventory.tax.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;

/**
 * Entity representing a GST return filing record.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "gst_returns")
public class GstReturn {

    @Id
    private String id;
    
    private String shopId;
    
    private String returnType; // GSTR1, GSTR3B
    
    private String period; // Format: YYYY-MM (e.g., "2026-01")
    
    private GstReturnStatus status;
    
    private BigDecimal totalTaxableValue;
    
    private BigDecimal totalCgst;
    
    private BigDecimal totalSgst;
    
    private BigDecimal totalIgst;
    
    private BigDecimal totalCess;
    
    private BigDecimal totalTaxLiability;
    
    private String jsonData; // Stored JSON for GST portal upload
    
    private String filedBy; // userId who filed
    
    private Instant filedAt;
    
    private Instant createdAt;
    
    private Instant updatedAt;
}

