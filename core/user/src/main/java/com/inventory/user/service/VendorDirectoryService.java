package com.inventory.user.service;

import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import java.util.Collection;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Vendor name lookups for reporting modules.
 *
 * <p>Exists so analytics can label rows without importing {@code VendorRepository}. Resolution is
 * batched: the previous report code fetched every invoice for the shop and then issued one
 * {@code findById} per distinct vendor, so a shop with many suppliers paid a query per supplier on
 * every report render.
 */
@Service
@RequiredArgsConstructor
public class VendorDirectoryService {

  private final VendorRepository vendorRepository;

  @Transactional(readOnly = true)
  public Optional<Vendor> findById(String vendorId) {
    return StringUtils.hasText(vendorId) ? vendorRepository.findById(vendorId) : Optional.empty();
  }

  /**
   * Display names keyed by vendor id.
   *
   * <p>Ids with no matching vendor map to themselves, so a row for a deleted vendor still renders
   * something identifiable rather than a blank cell.
   */
  @Transactional(readOnly = true)
  public Map<String, String> namesByIds(Collection<String> vendorIds) {
    Set<String> ids =
        vendorIds.stream().filter(StringUtils::hasText).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }

    Map<String, String> names = new LinkedHashMap<>();
    List<Vendor> vendors = vendorRepository.findAllById(ids);
    for (Vendor vendor : vendors) {
      if (vendor.getId() != null) {
        names.put(vendor.getId(), StringUtils.hasText(vendor.getName()) ? vendor.getName() : vendor.getId());
      }
    }
    for (String id : ids) {
      names.putIfAbsent(id, id);
    }
    return names;
  }
}
