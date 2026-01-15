package com.inventory.tax.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Validator for GST-related operations.
 */
@Component
public class GstValidator {
    
    private static final Pattern GSTIN_PATTERN = Pattern.compile(
        "^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$"
    );
    
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-\\d{2}$");
    
    /**
     * Validate period format (YYYY-MM).
     */
    public void validatePeriod(String period) {
        if (!StringUtils.hasText(period)) {
            throw new ValidationException("Period is required");
        }
        
        if (!PERIOD_PATTERN.matcher(period).matches()) {
            throw new ValidationException("Invalid period format. Expected YYYY-MM (e.g., 2026-01)");
        }
        
        try {
            YearMonth yearMonth = YearMonth.parse(period);
            YearMonth now = YearMonth.now();
            
            // Cannot generate for future periods
            if (yearMonth.isAfter(now)) {
                throw new ValidationException("Cannot generate GST return for future period");
            }
            
            // Reasonable past limit (e.g., 5 years)
            YearMonth minPeriod = now.minusYears(5);
            if (yearMonth.isBefore(minPeriod)) {
                throw new ValidationException("Period is too old. Maximum 5 years back allowed");
            }
        } catch (DateTimeParseException e) {
            throw new ValidationException("Invalid period: " + period);
        }
    }
    
    /**
     * Validate GSTIN format.
     */
    public void validateGstin(String gstin) {
        if (!StringUtils.hasText(gstin)) {
            throw new ValidationException("GSTIN is required");
        }
        
        if (!GSTIN_PATTERN.matcher(gstin.toUpperCase()).matches()) {
            throw new ValidationException("Invalid GSTIN format");
        }
    }
    
    /**
     * Validate return type.
     */
    public void validateReturnType(String returnType) {
        if (!StringUtils.hasText(returnType)) {
            throw new ValidationException("Return type is required");
        }
        
        if (!returnType.equals("GSTR1") && !returnType.equals("GSTR3B")) {
            throw new ValidationException("Invalid return type. Allowed: GSTR1, GSTR3B");
        }
    }
    
    /**
     * Validate shop ID.
     */
    public void validateShopId(String shopId) {
        if (!StringUtils.hasText(shopId)) {
            throw new ValidationException("Shop ID is required");
        }
    }
    
    /**
     * Validate user ID.
     */
    public void validateUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new ValidationException("User ID is required");
        }
    }
    
    /**
     * Validate GST summary request.
     */
    public void validateSummaryRequest(String shopId, String period) {
        validateShopId(shopId);
        validatePeriod(period);
    }
}

