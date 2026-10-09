package com.inventory.user.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.common.gst.Gstin;
import com.inventory.common.gst.PostalAddress;
import com.inventory.common.util.GstStateCode;
import com.inventory.user.rest.dto.request.CreateVendorRequest;
import com.inventory.user.rest.dto.request.SearchVendorRequest;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class VendorValidator {

  public static final String PLACE_REQUIRED =
      "Add the vendor's GSTIN, or at least the state on their address, so tax can be worked out";

  public void validateCreateRequest(CreateVendorRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Vendor name is required");
    }
    // Either phone or email must be provided
    boolean hasPhone = StringUtils.hasText(request.getContactPhone());
    boolean hasEmail = StringUtils.hasText(request.getContactEmail());
    if (!hasPhone && !hasEmail) {
      throw new ValidationException("Either vendor phone or vendor email is required");
    }
    validateGstin(request.getGstinUin());
    validateAddress(request.getPostalAddress());
    validatePlace(request.getGstinUin(), request.getPostalAddress());
  }

  /**
   * A GSTIN, when given, must pass the offline shape and check-character test. Blank is fine: an
   * unregistered supplier has none.
   */
  public void validateGstin(String gstinUin) {
    if (!StringUtils.hasText(gstinUin)) {
      return;
    }
    Optional<String> problem = Gstin.problem(gstinUin);
    if (problem.isPresent()) {
      throw new ValidationException("GSTIN: " + problem.get());
    }
  }

  /** A state code, when given, must be one GST knows. */
  public void validateAddress(PostalAddress address) {
    if (address == null || !StringUtils.hasText(address.getStateCode())) {
      return;
    }
    // format() turns a known code into "NN-Name" and hands back anything it does not know unchanged
    String code = address.getStateCode().trim();
    if (GstStateCode.format(code).equals(code)) {
      throw new ValidationException("Address: '" + address.getStateCode() + "' is not a GST state code");
    }
  }

  /**
   * The rule that makes IGST decidable: a vendor is placed by a valid GSTIN or by the state on
   * their address. With neither, every bill from them would have to be guessed as local.
   */
  public void validatePlace(String gstinUin, PostalAddress address) {
    boolean placedByGstin = Gstin.isValid(gstinUin);
    boolean placedByAddress = address != null && address.hasState();
    if (!placedByGstin && !placedByAddress) {
      throw new ValidationException(PLACE_REQUIRED);
    }
  }

  public void validateSearchRequest(SearchVendorRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getQuery())) {
      throw new ValidationException("Search query is required");
    }
  }

  public void validateVendorId(String vendorId) {
    if (!StringUtils.hasText(vendorId)) {
      throw new ValidationException("Vendor ID is required");
    }
  }

  public void validateUserIdExists(boolean exists, String userId) {
    if (!exists && StringUtils.hasText(userId)) {
      throw new ValidationException("User ID does not exist: " + userId);
    }
  }
}
