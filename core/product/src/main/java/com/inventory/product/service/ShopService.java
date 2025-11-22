package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.repository.ShopRepository;
import com.inventory.product.rest.dto.shop.RegisterShopRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalRequest;
import com.inventory.product.rest.dto.shop.ShopApprovalResponse;
import com.inventory.product.rest.dto.shop.ShopRegistrationResponse;
import com.inventory.product.rest.mapper.ShopMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ShopService {

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private ShopMapper shopMapper;

    @Transactional
    public ShopRegistrationResponse register(RegisterShopRequest request) {
        try {
            // Input validation
            if (request == null) {
                throw new ValidationException("Shop registration request cannot be null");
            }
            if (!StringUtils.hasText(request.getName())) {
                throw new ValidationException("Shop name is required");
            }
            if (!StringUtils.hasText(request.getLocation())) {
                throw new ValidationException("Shop location is required");
            }
            if (request.getInitialAdmin() == null) {
                throw new ValidationException("Initial admin details are required");
            }
            if (!StringUtils.hasText(request.getInitialAdmin().getEmail())) {
                throw new ValidationException("Initial admin email is required");
            }
            if (!StringUtils.hasText(request.getInitialAdmin().getName())) {
                throw new ValidationException("Initial admin name is required");
            }
            
            // Check for existing shop with the same business ID or name
            if (StringUtils.hasText(request.getBusinessId())) {
                shopRepository.findByBusinessId(request.getBusinessId()).ifPresent(shop -> {
                    throw new ResourceExistsException("Shop", "businessId", request.getBusinessId());
                });
            }
            
            // Check for existing shop with the same admin email
            shopRepository.findByInitialAdminEmail(request.getInitialAdmin().getEmail())
                .ifPresent(shop -> {
                    throw new ResourceExistsException("A shop with this admin email already exists");
                });
            
            log.info("Registering new shop: {}", request.getName());
            
            // Map request to entity using MapStruct
            Shop shop = shopMapper.toEntity(request);
            shop.setShopId("shop-" + UUID.randomUUID());
            shop.setStatus("PENDING");
            shop.setActive(false);
            shop.setUserLimit(0); // Will be set during approval
            shop.setUserCount(0);
            shop.setCreatedAt(Instant.now());
                    
            shop = shopRepository.save(shop);
            log.info("Successfully registered shop with ID: {}", shop.getShopId());
            
            return shopMapper.toRegistrationResponse(shop);
            
        } catch (ValidationException | ResourceExistsException e) {
            log.warn("Shop registration validation failed: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while registering shop: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error registering shop");
        } catch (Exception e) {
            log.error("Unexpected error while registering shop: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        }
    }

    @Transactional
    public ShopApprovalResponse approve(String shopId, ShopApprovalRequest request) {
        try {
            // Input validation
            if (request == null) {
                throw new ValidationException("Approval request cannot be null");
            }
            if (!StringUtils.hasText(shopId)) {
                throw new ValidationException("Shop ID is required");
            }
            
            log.debug("Processing shop approval for shop ID: {}", shopId);
            
            // Find the shop
            Shop shop = shopRepository.findById(shopId)
                    .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", shopId));
            
            // Check if already in the requested state
            if (request.isApprove() && "ACTIVE".equals(shop.getStatus())) {
                log.warn("Shop {} is already approved", shopId);
                return shopMapper.toApprovalResponse(shop);
            }
            
            if (!request.isApprove() && "REJECTED".equals(shop.getStatus())) {
                log.warn("Shop {} is already rejected", shopId);
                return shopMapper.toApprovalResponse(shop);
            }
            
            // Update shop status
            shop.setActive(request.isApprove());
            shop.setStatus(request.isApprove() ? "ACTIVE" : "REJECTED");
            
            // Set user limit if approving
            if (request.isApprove()) {
                if (request.getUserLimit() <= 0) {
                    throw new ValidationException("User limit must be greater than 0 when approving a shop");
                }
                shop.setUserLimit(request.getUserLimit());
                shop.setApprovedAt(Instant.now());
                log.info("Approving shop with ID: {} and user limit: {}", shopId, request.getUserLimit());
            } else {
                log.info("Rejecting shop with ID: {}", shopId);
            }
            
            shop = shopRepository.save(shop);
            log.info("Successfully updated shop status to: {}", shop.getStatus());
            
            return shopMapper.toApprovalResponse(shop);
            
        } catch (ValidationException | ResourceNotFoundException e) {
            log.warn("Shop approval validation failed: {}", e.getMessage());
            throw e;
        } catch (DataAccessException e) {
            log.error("Database error while processing shop approval: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error processing shop approval");
        } catch (Exception e) {
            log.error("Unexpected error while processing shop approval: {}", e.getMessage(), e);
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        }
    }
}

