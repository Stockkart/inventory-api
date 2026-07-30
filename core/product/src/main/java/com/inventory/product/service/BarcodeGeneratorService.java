package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.repository.BarcodePoolRepository;
import com.inventory.product.domain.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates short Code128-friendly unique barcodes scoped to a shop.
 * Format: {@code SK} + 4-char shop suffix + 8-char random (base36).
 */
@Service
public class BarcodeGeneratorService {

  private static final String PREFIX = "SK";
  private static final int RANDOM_LEN = 8;
  private static final int MAX_ATTEMPTS = 32;
  private static final char[] ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

  private final SecureRandom random = new SecureRandom();

  @Autowired
  private ProductRepository productRepository;

  @Autowired
  private BarcodePoolRepository barcodePoolRepository;

  public String generateUnique(String shopId) {
    if (!StringUtils.hasText(shopId)) {
      throw new ValidationException("Shop id is required to generate a barcode");
    }
    String shopSuffix = shopSuffix(shopId);
    for (int i = 0; i < MAX_ATTEMPTS; i++) {
      String code = PREFIX + shopSuffix + randomSegment(RANDOM_LEN);
      if (isAvailable(shopId, code)) {
        return code;
      }
    }
    throw new ValidationException("Could not generate a unique barcode; please try again");
  }

  public boolean isAvailable(String shopId, String code) {
    if (productRepository.findByShopIdAndBarcode(shopId, code).isPresent()) {
      return false;
    }
    return barcodePoolRepository.findByShopIdAndCode(shopId, code).isEmpty();
  }

  private static String shopSuffix(String shopId) {
    String cleaned = shopId.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    if (cleaned.length() >= 4) {
      return cleaned.substring(cleaned.length() - 4);
    }
    return (cleaned + "XXXX").substring(0, 4);
  }

  private String randomSegment(int len) {
    char[] buf = new char[len];
    for (int i = 0; i < len; i++) {
      buf[i] = ALPHABET[random.nextInt(ALPHABET.length)];
    }
    return new String(buf);
  }
}
