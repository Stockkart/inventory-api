package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.user.domain.model.ShopVendor;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.ShopVendorRepository;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.domain.repository.VendorRepository;
import com.inventory.user.rest.dto.request.CreateVendorRequest;
import com.inventory.user.rest.dto.request.SearchVendorRequest;
import com.inventory.user.rest.dto.response.CreateVendorResponse;
import com.inventory.user.rest.dto.response.UserShopListResponse;
import com.inventory.user.rest.dto.response.VendorDto;
import com.inventory.user.mapper.VendorMapper;
import com.inventory.user.validation.VendorValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional
public class VendorService {

  @Autowired
  private VendorRepository vendorRepository;

  @Autowired
  private ShopVendorRepository shopVendorRepository;

  @Autowired
  private VendorMapper vendorMapper;

  @Autowired
  private VendorValidator vendorValidator;

  @Autowired
  private UserAccountRepository userAccountRepository;

  @Autowired(required = false)
  private UserShopMembershipService membershipService;

  /**
   * Create a vendor and link it to a shop.
   *
   * @param shopId the shop ID
   * @param vendor the vendor entity to create
   * @return the created vendor
   */
  public Vendor createVendor(String shopId, Vendor vendor) {
    // Try to find existing vendor by email (if email provided)
    Vendor existingVendor = null;
    if (StringUtils.hasText(vendor.getContactEmail())) {
      existingVendor = vendorRepository.findByContactEmail(vendor.getContactEmail().trim())
          .orElse(null);
    }

    if (existingVendor == null) {
      vendorMapper.setTimestamps(vendor);
      vendor = vendorRepository.save(vendor);
      log.info("Created new vendor with ID: {}", vendor.getId());
    } else {
      // Update existing vendor if new info is provided
      boolean updated = false;
      if (StringUtils.hasText(vendor.getName()) && !vendor.getName().trim().equals(existingVendor.getName())) {
        existingVendor.setName(vendor.getName().trim());
        updated = true;
      }
      if (StringUtils.hasText(vendor.getContactPhone()) && !vendor.getContactPhone().trim().equals(existingVendor.getContactPhone())) {
        existingVendor.setContactPhone(vendor.getContactPhone().trim());
        updated = true;
      }
      if (StringUtils.hasText(vendor.getAddress()) && !vendor.getAddress().trim().equals(existingVendor.getAddress())) {
        existingVendor.setAddress(vendor.getAddress().trim());
        updated = true;
      }
      if (StringUtils.hasText(vendor.getCompanyName()) && !vendor.getCompanyName().trim().equals(existingVendor.getCompanyName())) {
        existingVendor.setCompanyName(vendor.getCompanyName().trim());
        updated = true;
      }
      if (StringUtils.hasText(vendor.getBusinessType()) && !vendor.getBusinessType().trim().equals(existingVendor.getBusinessType())) {
        existingVendor.setBusinessType(vendor.getBusinessType().trim());
        updated = true;
      }
      if (vendor.getGstinUin() != null && !vendor.getGstinUin().equals(existingVendor.getGstinUin())) {
        existingVendor.setGstinUin(StringUtils.hasText(vendor.getGstinUin()) ? vendor.getGstinUin().trim() : null);
        updated = true;
      }
      if (vendor.getUserId() != null && !vendor.getUserId().equals(existingVendor.getUserId())) {
        existingVendor.setUserId(StringUtils.hasText(vendor.getUserId()) ? vendor.getUserId().trim() : null);
        updated = true;
      }

      if (updated) {
        existingVendor.setUpdatedAt(Instant.now());
        existingVendor = vendorRepository.save(existingVendor);
        log.info("Updated vendor with ID: {}", existingVendor.getId());
      }
      vendor = existingVendor;
    }

    // Create shop-vendor relationship if it doesn't exist
    if (!shopVendorRepository.existsByShopIdAndVendorId(shopId, vendor.getId())) {
      ShopVendor shopVendor = vendorMapper.toShopVendor(shopId, vendor.getId());
      shopVendorRepository.save(shopVendor);
      log.info("Linked vendor {} to shop {}", vendor.getId(), shopId);
    }

    return vendor;
  }

  /**
   * Search vendors for a shop.
   *
   * @param shopId the shop ID
   * @param query the search query
   * @return list of matching vendors for the shop
   */
  @Transactional(readOnly = true)
  public List<Vendor> searchVendors(String shopId, String query) {
    // Get all vendor IDs for this shop
    List<String> vendorIds = shopVendorRepository.findByShopId(shopId).stream()
        .map(ShopVendor::getVendorId)
        .collect(Collectors.toList());

    if (vendorIds.isEmpty()) {
      return List.of();
    }

    // If query provided, search vendors and filter by shop's vendors
    if (StringUtils.hasText(query)) {
      List<Vendor> allMatchingVendors = vendorRepository.searchByQuery(query.trim());
      return allMatchingVendors.stream()
          .filter(v -> vendorIds.contains(v.getId()))
          .collect(Collectors.toList());
    }

    // If no query, return all vendors for the shop
    return vendorRepository.findAllById(vendorIds);
  }

  /**
   * Check if a vendor is linked to a shop.
   *
   * @param shopId the shop ID
   * @param vendorId the vendor ID
   * @return true if linked, false otherwise
   */
  @Transactional(readOnly = true)
  public boolean isVendorLinkedToShop(String shopId, String vendorId) {
    return shopVendorRepository.existsByShopIdAndVendorId(shopId, vendorId);
  }

  /**
   * Create a vendor for a shop.
   *
   * @param request the create vendor request
   * @param shopId the shop ID
   * @return the create vendor response
   */
  public CreateVendorResponse createVendor(CreateVendorRequest request, String shopId) {
    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    // Validate request
    vendorValidator.validateCreateRequest(request);

    if (StringUtils.hasText(request.getUserId())) {
      vendorValidator.validateUserIdExists(
          userAccountRepository.findById(request.getUserId().trim()).isPresent(),
          request.getUserId());
    }

    log.info("Creating vendor for shop: {}", shopId);

    try {
      Vendor vendor = vendorMapper.toEntity(request);
      vendorMapper.setTimestamps(vendor);

      // Create vendor and link to shop
      vendor = createVendor(shopId, vendor);

      // Map to response
      CreateVendorResponse response = vendorMapper.toCreateResponse(vendor);

      log.info("Successfully created vendor with ID: {} for shop: {}", vendor.getId(), shopId);
      return response;

    } catch (ValidationException e) {
      log.warn("Validation error in create vendor: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while creating vendor: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Error creating vendor");
    } catch (Exception e) {
      log.error("Unexpected error while creating vendor: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Failed to create vendor");
    }
  }

  /**
   * Search vendors by generic query for a shop.
   * Searches across name, companyName, contactEmail, contactPhone, and address fields using regex.
   *
   * @param request the search vendor request containing the query
   * @param shopId the shop ID
   * @return list of matching vendor DTOs
   */
  @Transactional(readOnly = true)
  public List<VendorDto> searchVendor(SearchVendorRequest request, String shopId) {
    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    vendorValidator.validateSearchRequest(request);

    log.info("Searching vendors by query '{}' for shop: {}", request.getQuery(), shopId);

    try {
      // Search vendors using generic query (searches name, companyName, email, phone, address)
      List<Vendor> matchingVendors = searchVendors(shopId, request.getQuery().trim());

      // Map all matching vendors to DTOs
      List<VendorDto> vendorDtos = matchingVendors.stream()
          .map(vendorMapper::toDto)
          .collect(Collectors.toList());

      log.info("Found {} vendor(s) matching query '{}' for shop: {}", vendorDtos.size(), request.getQuery(), shopId);
      return vendorDtos;

    } catch (ValidationException e) {
      log.warn("Search vendor failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while searching vendors: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Error searching vendors");
    } catch (Exception e) {
      log.error("Unexpected error while searching vendors: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Failed to search vendors");
    }
  }

  /**
   * Get vendor by ID for a shop.
   * Validates that the vendor is linked to the shop.
   *
   * @param vendorId the vendor ID
   * @param shopId the shop ID
   * @return the vendor DTO
   * @throws ResourceNotFoundException if vendor not found or not linked to shop
   */
  @Transactional(readOnly = true)
  public VendorDto getVendorById(String vendorId, String shopId) {
    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    vendorValidator.validateVendorId(vendorId);

    log.info("Getting vendor with ID: {} for shop: {}", vendorId, shopId);

    try {
      // Find vendor
      Vendor vendor = vendorRepository.findById(vendorId)
          .orElseThrow(() -> new ResourceNotFoundException("Vendor", "id", vendorId));

      // Verify vendor is linked to the shop
      if (!isVendorLinkedToShop(shopId, vendorId)) {
        throw new ResourceNotFoundException("Vendor", "id", 
            "Vendor not found or not linked to your shop");
      }

      // Map to DTO
      VendorDto vendorDto = vendorMapper.toDto(vendor);

      log.info("Successfully retrieved vendor with ID: {} for shop: {}", vendorId, shopId);
      return vendorDto;

    } catch (ResourceNotFoundException e) {
      log.warn("Vendor not found: {} for shop: {}", vendorId, shopId);
      throw e;
    } catch (ValidationException e) {
      log.warn("Validation error in get vendor by ID: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting vendor: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Error retrieving vendor");
    } catch (Exception e) {
      log.error("Unexpected error while getting vendor: {}", e.getMessage(), e);
      throw new BaseException(
          ErrorCode.INTERNAL_SERVER_ERROR,
          "Failed to retrieve vendor");
    }
  }

  /**
   * Get shops for a vendor when the vendor is a StockKart user (has userId).
   * Used when buyer assigns credit to vendor's shop - they can pick which shop.
   *
   * @param vendorId the vendor ID
   * @param callerShopId the buyer's shop (vendor must be linked to this shop)
   * @return list of shops the vendor user has access to, or empty if vendor is not a user
   */
  @Transactional(readOnly = true)
  public UserShopListResponse getShopsForVendor(
      String vendorId, String callerShopId) {
    if (!StringUtils.hasText(vendorId) || !StringUtils.hasText(callerShopId)) {
      return new UserShopListResponse(Collections.emptyList());
    }
    Vendor vendor = vendorRepository.findById(vendorId).orElse(null);
    if (vendor == null || !StringUtils.hasText(vendor.getUserId())) {
      return new UserShopListResponse(Collections.emptyList());
    }
    if (!isVendorLinkedToShop(callerShopId, vendorId)) {
      return new UserShopListResponse(Collections.emptyList());
    }
    if (membershipService == null) {
      return new UserShopListResponse(Collections.emptyList());
    }
    return membershipService.getShopsForUser(vendor.getUserId());
  }
}

