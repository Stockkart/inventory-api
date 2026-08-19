package com.inventory.taxation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Official HSN/SAC descriptions from {@code classpath:hsn/hsn-sac-master.json}.
 */
@Component
@Slf4j
public class HsnSacCatalog {

  static final String CLASSPATH_RESOURCE = "classpath:hsn/hsn-sac-master.json";

  private final Map<String, String> byCode;

  @Autowired
  public HsnSacCatalog(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
    this(load(objectMapper, resourceLoader.getResource(CLASSPATH_RESOURCE)));
  }

  HsnSacCatalog(Map<String, String> byCode) {
    this.byCode = Map.copyOf(byCode);
  }

  public Optional<String> descriptionFor(String hsnOrSac) {
    String digits = digitsOnly(hsnOrSac);
    if (!StringUtils.hasText(digits) || "0".equals(digits)) {
      return Optional.empty();
    }
    String exact = byCode.get(digits);
    if (exact != null) {
      return Optional.of(exact);
    }
    if (digits.length() > 6) {
      String six = byCode.get(digits.substring(0, 6));
      if (six != null) {
        return Optional.of(six);
      }
    }
    if (digits.length() > 4) {
      String four = byCode.get(digits.substring(0, 4));
      if (four != null) {
        return Optional.of(four);
      }
    }
    if (digits.length() > 2) {
      String two = byCode.get(digits.substring(0, 2));
      if (two != null) {
        return Optional.of(two);
      }
    }
    return Optional.empty();
  }

  static String digitsOnly(String raw) {
    if (!StringUtils.hasText(raw)) {
      return "";
    }
    StringBuilder out = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c >= '0' && c <= '9') {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static Map<String, String> load(ObjectMapper objectMapper, Resource resource) {
    if (resource == null || !resource.exists()) {
      throw new IllegalStateException("Missing HSN/SAC master: " + CLASSPATH_RESOURCE);
    }
    try (InputStream in = resource.getInputStream()) {
      Map<String, String> loaded = objectMapper.readValue(in, new TypeReference<LinkedHashMap<String, String>>() {});
      log.info("Loaded {} HSN/SAC descriptions from {}", loaded.size(), CLASSPATH_RESOURCE);
      return loaded;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load " + CLASSPATH_RESOURCE, e);
    }
  }
}
