package com.inventory.taxation.service;

import com.inventory.common.util.HsnCodes;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

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
    return HsnCodes.mostSpecific(hsnOrSac, byCode::get, 2);
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
