package com.inventory.product.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceExistsException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.BusinessType;
import com.inventory.product.domain.repository.BusinessTypeRepository;
import com.inventory.product.rest.dto.business.BusinessTypeResponse;
import com.inventory.product.rest.dto.business.CreateBusinessTypeRequest;
import com.inventory.product.rest.mapper.BusinessTypeMapper;
import com.inventory.product.validation.BusinessTypeValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
public class BusinessTypeService {

  @Autowired
  private BusinessTypeRepository repository;

  @Autowired
  private BusinessTypeMapper mapper;

  @Autowired
  private BusinessTypeValidator businessTypeValidator;

  @Transactional
  public BusinessTypeResponse create(CreateBusinessTypeRequest request) {
    try {
      // Input validation using BusinessTypeValidator
      businessTypeValidator.validateCreateRequest(request);

      log.info("Creating new business type with code: {}", request.getCode());

      // Check if business type with the same code already exists
      if (repository.findById(request.getCode()).isPresent()) {
        throw new ResourceExistsException("Business type with code " + request.getCode() + " already exists");
      }

      // Map and save the business type
      BusinessType entity = mapper.toEntity(request);
      entity = repository.save(entity);

      log.info("Successfully created business type with ID: {}", entity.getId());

      return mapper.toResponse(entity);

    } catch (ValidationException | ResourceExistsException e) {
      log.warn("Business type creation failed: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while creating business type: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error creating business type");
    } catch (Exception e) {
      log.error("Unexpected error while creating business type: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  /**
   * Retrieves a business type by its ID.
   *
   * @param id the business type ID
   * @return the business type response
   * @throws ResourceNotFoundException if the business type is not found
   */
  public BusinessTypeResponse getById(String id) {
    try {
      log.debug("Fetching business type with ID: {}", id);

      BusinessType businessType = repository.findById(id)
          .orElseThrow(() -> new ResourceNotFoundException("Business type", "id", id));

      return mapper.toResponse(businessType);

    } catch (ResourceNotFoundException e) {
      log.warn("Business type not found with ID: {}", id);
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while fetching business type with ID {}: {}", id, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving business type");
    } catch (Exception e) {
      log.error("Unexpected error while fetching business type: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }

  /**
   * Updates a business type's status (enabled/disabled).
   *
   * @param id      the business type ID
   * @param enabled whether the business type should be enabled or disabled
   * @return the updated business type response
   * @throws ResourceNotFoundException if the business type is not found
   */
  @Transactional
  public BusinessTypeResponse updateStatus(String id, boolean enabled) {
    try {
      log.info("Updating status for business type with ID: {} to {}", id, enabled ? "enabled" : "disabled");

      BusinessType businessType = repository.findById(id)
          .orElseThrow(() -> new ResourceNotFoundException("Business type", "id", id));

      // Only update if status is changing
      if (businessType.isEnabled() != enabled) {
        businessType.setEnabled(enabled);
        businessType = repository.save(businessType);
        log.info("Successfully updated status for business type with ID: {}", id);
      } else {
        log.debug("Business type with ID: {} already has the requested status: {}", id, enabled);
      }

      return mapper.toResponse(businessType);

    } catch (ResourceNotFoundException e) {
      log.warn("Cannot update status - business type not found with ID: {}", id);
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while updating business type status with ID {}: {}", id, e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error updating business type status");
    } catch (Exception e) {
      log.error("Unexpected error while updating business type status: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
  }
}
