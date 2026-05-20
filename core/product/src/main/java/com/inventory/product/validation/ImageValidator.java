package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates image files and bytes for upload and parsing operations.
 */
@Component
public class ImageValidator {

  /**
   * Validate MultipartFile for image upload (strict - must be image/*).
   */
  public void validateImageFile(MultipartFile image) {
    if (image == null || image.isEmpty()) {
      throw new ValidationException("Image file is required");
    }
    String contentType = image.getContentType();
    if (contentType == null || !contentType.startsWith("image/")) {
      throw new ValidationException("File must be an image");
    }
  }

  /**
   * Validate image bytes before passing to OCR/parsing.
   */
  public void validateImageBytes(byte[] imageBytes) {
    if (imageBytes == null || imageBytes.length == 0) {
      throw new ValidationException("Image bytes are empty");
    }
  }

  /**
   * Validate MultipartFile for invoice parsing. More permissive - allows application/octet-stream.
   */
  public void validateImageFileForParsing(MultipartFile image) {
    if (image == null || image.isEmpty()) {
      throw new ValidationException("Image file is empty");
    }
    String contentType = image.getContentType();
    if (contentType == null || (!contentType.startsWith("image/") && !contentType.equals("application/octet-stream"))) {
      throw new ValidationException("File must be an image");
    }
  }

  /** Max images per multi-page invoice parse request. */
  public static final int MAX_INVOICE_IMAGES = 20;

  /** Max bytes per invoice image (10 MB). */
  public static final long MAX_INVOICE_IMAGE_BYTES = 10L * 1024 * 1024;

  public void validateInvoiceImageBatch(java.util.List<MultipartFile> images) {
    if (images == null || images.isEmpty()) {
      throw new ValidationException("At least one image is required");
    }
    if (images.size() > MAX_INVOICE_IMAGES) {
      throw new ValidationException("At most " + MAX_INVOICE_IMAGES + " images per request");
    }
    for (int i = 0; i < images.size(); i++) {
      MultipartFile image = images.get(i);
      validateImageFileForParsing(image);
      if (image.getSize() > MAX_INVOICE_IMAGE_BYTES) {
        throw new ValidationException(
            "Image " + (i + 1) + " exceeds maximum size of 10 MB");
      }
    }
  }
}
