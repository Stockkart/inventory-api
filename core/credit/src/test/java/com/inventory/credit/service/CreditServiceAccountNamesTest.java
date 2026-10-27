package com.inventory.credit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditPartyType;
import com.inventory.credit.domain.repository.CreditAccountRepository;
import com.inventory.credit.domain.repository.CreditEntryRepository;
import com.inventory.credit.rest.dto.response.CreditAccountResponse;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.domain.model.Customer;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.CustomerRepository;
import com.inventory.user.domain.repository.VendorRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The credit accounts list names each account from its customer / vendor with one read per party
 * type, not one per account, and keeps the old naming rules.
 */
class CreditServiceAccountNamesTest {

  private static final String SHOP = "shop-1";

  private CreditAccountRepository accounts;
  private CustomerRepository customers;
  private VendorRepository vendors;
  private CreditService service;
  private final List<CreditAccount> rows = new ArrayList<>();
  private Instant at = Instant.parse("2026-10-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    accounts = mock(CreditAccountRepository.class);
    customers = mock(CustomerRepository.class);
    vendors = mock(VendorRepository.class);
    service = new CreditService(accounts, mock(CreditEntryRepository.class), customers, vendors,
        mock(MetricsWrapper.class), mock(CreditBalances.class));
    when(accounts.findByShopId(SHOP)).thenReturn(rows);
  }

  private void account(String id, CreditPartyType type, String partyId, String label) {
    CreditAccount a = new CreditAccount();
    a.setId(id);
    a.setShopId(SHOP);
    a.setPartyType(type);
    a.setPartyRefId(partyId);
    a.setPartyDisplayName(label);
    at = at.minusSeconds(60); // listed in insertion order
    a.setLastEntryAt(at);
    rows.add(a);
  }

  private static Customer customer(String id, String name) {
    Customer c = new Customer();
    c.setId(id);
    c.setName(name);
    return c;
  }

  private static Vendor vendor(String id, String name) {
    Vendor v = new Vendor();
    v.setId(id);
    v.setName(name);
    return v;
  }

  @Test
  void oneReadPerPartyTypeWithTheOldNamingRules() {
    account("a1", CreditPartyType.CUSTOMER, "c1", "old label");          // found: name wins, trimmed
    account("a2", CreditPartyType.CUSTOMER, " c2 ", "Customer c2");      // padded id; found
    account("a3", CreditPartyType.CUSTOMER, "c3", "Customer c3");        // gone: id label -> "Customer"
    account("a4", CreditPartyType.CUSTOMER, "c4", "Asha (walk-in)");     // gone: real label kept
    account("a5", CreditPartyType.CUSTOMER, "c5", "kept");               // found but blank name: label kept
    account("a6", CreditPartyType.VENDOR, "v1", "Vendor v1");            // found
    account("a7", CreditPartyType.VENDOR, "v2", null);                   // gone, no label -> "Vendor"
    account("a8", CreditPartyType.CUSTOMER, "  ", "blank id");           // nothing to look up
    account("a9", CreditPartyType.CUSTOMER, "c1", "again");              // same customer twice
    when(customers.findAllById(anyIterable())).thenReturn(List.of(
        customer("c1", "  Ravi Medical "), customer("c2", "Nafees"), customer("c5", "   ")));
    when(vendors.findAllById(anyIterable())).thenReturn(List.of(vendor("v1", "Cipla Distributors")));

    List<CreditAccountResponse> out = service.listAccountResponses(SHOP);

    Map<String, String> names = out.stream()
        .collect(Collectors.toMap(CreditAccountResponse::getId, r -> String.valueOf(r.getPartyDisplayName())));
    assertThat(names).containsExactlyInAnyOrderEntriesOf(Map.of(
        "a1", "Ravi Medical", "a2", "Nafees", "a3", "Customer", "a4", "Asha (walk-in)", "a5", "kept",
        "a6", "Cipla Distributors", "a7", "Vendor", "a8", "blank id", "a9", "Ravi Medical"));
    assertThat(out).extracting(CreditAccountResponse::getId)
        .containsExactly("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Iterable<String>> asked = ArgumentCaptor.forClass(Iterable.class);
    verify(customers, times(1)).findAllById(asked.capture());
    assertThat(asked.getValue()).containsExactlyInAnyOrder("c1", "c2", "c3", "c4", "c5");
    verify(vendors, times(1)).findAllById(any());
    verify(customers, never()).findById(anyString());
    verify(vendors, never()).findById(anyString());
  }

  @Test
  void noAccountsOfATypeMeansNoReadForIt() {
    account("a1", CreditPartyType.VENDOR, "v1", "x");
    when(vendors.findAllById(anyIterable())).thenReturn(List.of());

    service.listAccountResponses(SHOP);

    verify(customers, never()).findAllById(any());
    verify(vendors, times(1)).findAllById(any());
  }
}
