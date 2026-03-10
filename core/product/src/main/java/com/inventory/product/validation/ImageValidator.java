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
}
