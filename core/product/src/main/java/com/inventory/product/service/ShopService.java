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
import com.inventory.product.validation.ShopValidator;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.service.UserShopMembershipService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;

@Service
@Slf4j
@Transactional(readOnly = true)
public class ShopService {

  @Autowired
  private ShopRepository shopRepository;

  @Autowired
  private ShopMapper shopMapper;

  @Autowired
  private ShopValidator shopValidator;

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired
  @Lazy
  private UserShopMembershipService membershipService;

  @Value("${plan.trial-days:30}")
  private int trialDays;

  @Transactional
  public ShopRegistrationResponse register(RegisterShopRequest request, String userId) {
    try {
      // Input validation using ShopValidator
      shopValidator.validateRegisterRequest(request);

      if (userId == null || userId.trim().isEmpty()) {
        throw new ValidationException("User ID is required");
      }

      // Check for existing shops with the same contact email
      // Allow duplicate only when the registering user owns ALL shops with that email (multi-shop)
      for (Shop existingShop : shopRepository.findAllByContactEmail(request.getContactEmail())) {
        if (!membershipService.hasOwnerAccess(userId, existingShop.getShopId())) {
          throw new ResourceExistsException("A shop with this contact email already exists");
        }
      }

      com.inventory.user.domain.model.UserAccount userAccount = userAccountRepository.findById(userId)
          .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));

      // Multi-shop: user can own multiple shops - no "already has shop" check

      log.info("Registering new shop: {} for user: {}", request.getName(), userId);

      // Map request to entity using MapStruct (sets defaults: APPROVED, active=true)
      // MongoDB will auto-generate the shopId as ObjectId
      Shop shop = shopMapper.toEntity(request);
      shop.setUserLimit(0); // Can be set later if needed
      shop.setPlanId(null); // Trial: no plan purchased yet
      shop.setPlanExpiryDate(Instant.now().plus(trialDays, ChronoUnit.DAYS)); // trial from config

      // Save shop first
      shop = shopRepository.save(shop);

      // Add membership (multi-shop)
      membershipService.addMembership(userId, shop.getShopId(), UserRole.OWNER,
          UserShopMembershipService.RELATIONSHIP_OWNER);

      // Update user account with shopId as active shop (backward compatible)
      shopMapper.updateUserAccountWithShopId(userAccount, shop.getShopId());
      userAccount.setRole(UserRole.OWNER);
      userAccount.setUpdatedAt(Instant.now());
      userAccountRepository.save(userAccount);

      log.info("Successfully registered shop with ID: {} and updated user account: {}", shop.getShopId(), userId);

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
      // Input validation using ShopValidator
      shopValidator.validateApprovalRequest(shopId, request);

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

  /**
   * Get shop plan info for plan module. Used by ShopProviderImpl adapter.
   */
  @Transactional(readOnly = true)
  public Optional<ShopPlanInfo> getShopPlanInfo(String shopId) {
    return shopRepository.findById(shopId)
        .map(shop -> new ShopPlanInfo(shop.getShopId(), shop.getPlanId(), shop.getPlanExpiryDate()));
  }

  /**
   * Update shop's plan. Used by ShopProviderImpl adapter.
   */
  @Transactional
  public void updatePlan(String shopId, String planId, Instant expiryDate) {
    Shop shop = shopRepository.findById(shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Shop", "id", shopId));
    shop.setPlanId(planId);
    shop.setPlanExpiryDate(expiryDate);
    shopRepository.save(shop);
  }

  /**
   * Get shop name. Used by ShopServiceAdapterImpl.
   */
  @Transactional(readOnly = true)
  public String getShopName(String shopId) {
    return shopRepository.findById(shopId).map(Shop::getName).orElse(null);
  }

  /**
   * Check if shop exists. Used by ShopServiceAdapterImpl.
   */
  @Transactional(readOnly = true)
  public boolean shopExists(String shopId) {
    return shopRepository.existsById(shopId);
  }

  /**
   * Get shop tax info. Used by ShopServiceAdapterImpl.
   */
  @Transactional(readOnly = true)
  public com.inventory.user.service.ShopServiceAdapter.ShopTaxInfo getShopTaxInfo(String shopId) {
    return shopRepository.findById(shopId)
        .map(shop -> new com.inventory.user.service.ShopServiceAdapter.ShopTaxInfo(
            shop.getSgst(), shop.getCgst(), shop.getName()))
        .orElse(null);
  }

  /** DTO for plan module. */
  public record ShopPlanInfo(String shopId, String planId, Instant planExpiryDate) {}
}

