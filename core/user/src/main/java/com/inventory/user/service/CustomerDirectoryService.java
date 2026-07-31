package com.inventory.user.service;

import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.repository.CustomerRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Customer lookups for reporting modules.
 *
 * <p>Exists so analytics can label rows without importing {@code CustomerRepository}.
 */
@Service
@RequiredArgsConstructor
public class CustomerDirectoryService {

  private final CustomerRepository customerRepository;

  @Transactional(readOnly = true)
  public Optional<Customer> findById(String customerId) {
    return StringUtils.hasText(customerId)
        ? customerRepository.findById(customerId)
        : Optional.empty();
  }

  /**
   * Display names keyed by customer id, resolved in one round trip.
   *
   * <p>Ids with no matching customer map to themselves so a row for a deleted customer still
   * renders something identifiable.
   */
  @Transactional(readOnly = true)
  public Map<String, String> namesByIds(Collection<String> customerIds) {
    Set<String> ids = customerIds.stream().filter(StringUtils::hasText).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }

    Map<String, String> names = new LinkedHashMap<>();
    for (Customer customer : customerRepository.findAllById(ids)) {
      if (customer.getId() != null) {
        names.put(
            customer.getId(),
            StringUtils.hasText(customer.getName()) ? customer.getName() : customer.getId());
      }
    }
    for (String id : ids) {
      names.putIfAbsent(id, id);
    }
    return names;
  }
}
