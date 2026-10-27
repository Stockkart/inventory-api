package com.inventory.product.mapper;

import com.inventory.user.domain.model.Customer;
import java.util.Optional;
import java.util.function.Function;

/**
 * How {@link PurchaseMapper} finds the customer linked to a purchase: one lookup per purchase for
 * single responses, or a map prefetched in one query for list pages (passed to MapStruct as
 * {@code @Context}).
 */
public final class CustomerLookup {

  private final Function<String, Optional<Customer>> finder;

  private CustomerLookup(Function<String, Optional<Customer>> finder) {
    this.finder = finder;
  }

  public static CustomerLookup of(Function<String, Optional<Customer>> finder) {
    return new CustomerLookup(finder);
  }

  public Optional<Customer> find(String customerId) {
    return finder.apply(customerId);
  }
}
