package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates Excel stock sheet files for migration.
 */
@Component
public class StockSheetValidator {

  private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
  private static final String[] ALLOWED_EXTENSIONS = {".xls", ".xlsx"};

  public void validateStockSheet(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ValidationException("Excel file is required");
    }
    if (file.getSize() > MAX_FILE_SIZE_BYTES) {
      throw new ValidationException("File size must not exceed 10 MB");
    }
    String filename = file.getOriginalFilename();
    if (filename == null || filename.isBlank()) {
      throw new ValidationException("Filename is required");
    }
    String lower = filename.toLowerCase();
    boolean validExt = false;
    for (String ext : ALLOWED_EXTENSIONS) {
      if (lower.endsWith(ext)) {
        validExt = true;
        break;
      }
    }
    if (!validExt) {
      throw new ValidationException("File must be Excel format (.xls or .xlsx)");
    }
  }
}
