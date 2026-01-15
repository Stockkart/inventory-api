package com.inventory.tax.service;

import com.inventory.tax.domain.model.GstReturn;
import com.inventory.tax.domain.model.GstReturnStatus;
import com.inventory.tax.domain.repository.GstReturnRepository;
import com.inventory.tax.rest.dto.GstReturnDto;
import com.inventory.tax.rest.dto.GstReturnListResponse;
import com.inventory.tax.rest.dto.GstSummaryResponse;
import com.inventory.tax.rest.dto.Gstr1Response;
import com.inventory.tax.rest.dto.Gstr3bResponse;
import com.inventory.tax.rest.mapper.GstReturnMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Main service for GST return operations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class GstReturnService {
    
    private final GstReturnRepository gstReturnRepository;
    private final GstAggregationService aggregationService;
    private final Gstr1Service gstr1Service;
    private final Gstr3bService gstr3bService;
    private final GstReturnMapper gstReturnMapper;
    
    /**
     * Get GST summary for a period.
     */
    public GstSummaryResponse getSummary(String shopId, String period) {
        log.info("Getting GST summary for shop {} period {}", shopId, period);
        return aggregationService.generateSummary(shopId, period);
    }
    
    /**
     * Generate GSTR-1 report.
     */
    public Gstr1Response getGstr1(String shopId, String period) {
        log.info("Getting GSTR-1 for shop {} period {}", shopId, period);
        return gstr1Service.generateGstr1(shopId, period);
    }
    
    /**
     * Generate GSTR-3B report.
     */
    public Gstr3bResponse getGstr3b(String shopId, String period) {
        log.info("Getting GSTR-3B for shop {} period {}", shopId, period);
        return gstr3bService.generateGstr3b(shopId, period);
    }
    
    /**
     * Save/update a GST return record.
     */
    @Transactional
    public GstReturnDto saveGstReturn(String shopId, String returnType, String period, 
                                       GstSummaryResponse summary, String userId) {
        log.info("Saving GST return for shop {} type {} period {}", shopId, returnType, period);
        
        // Check if return already exists
        Optional<GstReturn> existing = gstReturnRepository.findByShopIdAndReturnTypeAndPeriod(
            shopId, returnType, period
        );
        
        GstReturn gstReturn;
        if (existing.isPresent()) {
            gstReturn = existing.get();
            gstReturn.setTotalTaxableValue(summary.getTotalTaxableValue());
            gstReturn.setTotalCgst(summary.getTotalCgst());
            gstReturn.setTotalSgst(summary.getTotalSgst());
            gstReturn.setTotalIgst(summary.getTotalIgst());
            gstReturn.setTotalCess(summary.getTotalCess());
            gstReturn.setTotalTaxLiability(summary.getTotalTaxLiability());
            gstReturn.setStatus(GstReturnStatus.GENERATED);
            gstReturn.setUpdatedAt(Instant.now());
        } else {
            gstReturn = GstReturn.builder()
                .shopId(shopId)
                .returnType(returnType)
                .period(period)
                .status(GstReturnStatus.GENERATED)
                .totalTaxableValue(summary.getTotalTaxableValue())
                .totalCgst(summary.getTotalCgst())
                .totalSgst(summary.getTotalSgst())
                .totalIgst(summary.getTotalIgst())
                .totalCess(summary.getTotalCess())
                .totalTaxLiability(summary.getTotalTaxLiability())
                .filedBy(userId)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        }
        
        gstReturn = gstReturnRepository.save(gstReturn);
        return gstReturnMapper.toDto(gstReturn);
    }
    
    /**
     * List all GST returns for a shop.
     */
    public GstReturnListResponse listReturns(String shopId) {
        log.info("Listing GST returns for shop {}", shopId);
        
        List<GstReturn> returns = gstReturnRepository.findByShopId(shopId);
        List<GstReturnDto> dtos = returns.stream()
            .map(gstReturnMapper::toDto)
            .toList();
        
        return GstReturnListResponse.builder()
            .returns(dtos)
            .page(1)
            .limit(dtos.size())
            .total(dtos.size())
            .totalPages(1)
            .build();
    }
    
    /**
     * Get a specific GST return.
     */
    public Optional<GstReturnDto> getReturn(String returnId) {
        return gstReturnRepository.findById(returnId)
            .map(gstReturnMapper::toDto);
    }
    
    /**
     * Mark return as filed.
     */
    @Transactional
    public GstReturnDto markAsFiled(String returnId, String userId) {
        log.info("Marking return {} as filed by user {}", returnId, userId);
        
        GstReturn gstReturn = gstReturnRepository.findById(returnId)
            .orElseThrow(() -> new IllegalArgumentException("GST Return not found: " + returnId));
        
        gstReturn.setStatus(GstReturnStatus.FILED);
        gstReturn.setFiledBy(userId);
        gstReturn.setFiledAt(Instant.now());
        gstReturn.setUpdatedAt(Instant.now());
        
        gstReturn = gstReturnRepository.save(gstReturn);
        return gstReturnMapper.toDto(gstReturn);
    }
}

