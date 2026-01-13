package com.inventory.ocr.config;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * Configuration class for Tesseract OCR setup.
 * Handles Tesseract initialization, datapath configuration, and language settings.
 */
@Configuration
@Slf4j
public class OcrConfig {

  /**
   * Creates and configures a Tesseract instance.
   * 
   * @return configured Tesseract bean
   */
  @Bean
  public Tesseract tesseract() {
    Tesseract tesseract = new Tesseract();
    
    // Find and set tessdata path
    String datapath = findTessdataPath();
    if (datapath != null) {
      try {
        tesseract.setDatapath(datapath);
        log.info("Tesseract datapath set to: {}", datapath);
      } catch (Exception e) {
        log.warn("Failed to set datapath to '{}', using system default: {}", datapath, e.getMessage());
      }
    } else {
      log.info("Using system default tessdata path (Tesseract will auto-detect)");
    }
    
    // Set language (default is English)
    tesseract.setLanguage("eng");
    
    // Set OCR engine mode
    tesseract.setPageSegMode(1);
    
    // Set OCR engine mode to use LSTM neural network
    tesseract.setOcrEngineMode(1);
    
    log.info("Tesseract OCR bean configured and initialized");
    
    return tesseract;
  }

  /**
   * Finds the tessdata directory path.
   * Checks TESSDATA_PREFIX environment variable first, then common system paths.
   * 
   * @return tessdata directory path, or null if not found
   */
  private String findTessdataPath() {
    // Check TESSDATA_PREFIX environment variable first (common in Docker containers)
    String tessdataPrefix = System.getenv("TESSDATA_PREFIX");
    if (tessdataPrefix != null && !tessdataPrefix.isEmpty()) {
      File tessdataDir = new File(tessdataPrefix);
      if (tessdataDir.exists() && tessdataDir.isDirectory()) {
        log.info("Using TESSDATA_PREFIX for tessdata path: {}", tessdataPrefix);
        return tessdataPrefix;
      } else {
        log.warn("TESSDATA_PREFIX is set to '{}' but directory does not exist", tessdataPrefix);
      }
    }
    
    // Try common system paths
    String[] commonPaths = {
        "/usr/share/tesseract-ocr/5/tessdata",      // Tesseract 5.x (Debian/Ubuntu)
        "/usr/share/tesseract-ocr/4.00/tessdata",   // Tesseract 4.x (Debian/Ubuntu)
        "/usr/share/tesseract-ocr/tessdata",        // Older versions
        "/usr/local/share/tessdata",                 // macOS Homebrew
        System.getProperty("user.dir") + "/tessdata" // Custom project path
    };
    
    for (String path : commonPaths) {
      File dir = new File(path);
      if (dir.exists() && dir.isDirectory()) {
        log.info("Found tessdata at: {}", path);
        return path;
      }
    }
    
    log.warn("Could not find tessdata directory in any common location");
    return null;
  }
}

