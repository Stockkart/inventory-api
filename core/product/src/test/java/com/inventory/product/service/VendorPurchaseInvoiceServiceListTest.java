package com.inventory.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.VendorPurchaseInvoice;
import com.inventory.product.domain.repository.VendorPurchaseInvoiceRepository;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceListResponse;
import com.inventory.product.rest.dto.response.VendorPurchaseInvoiceSummaryDto;
import com.inventory.user.domain.model.Vendor;
import com.inventory.user.domain.repository.VendorRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * The purchase history filters. A date range used to be applied in the browser to whatever
 * hundred bills had been entered most recently, so a shop with more than a hundred bills saw
 * nothing for any earlier period.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VendorPurchaseInvoiceServiceListTest {

  private static final String SHOP = "shop-1";

  @Mock private VendorPurchaseInvoiceRepository repository;
  @Mock private VendorRepository vendorRepository;

  @InjectMocks private VendorPurchaseInvoiceService service;

  @BeforeEach
  void vendors() {
    when(vendorRepository.findAllById(anyIterable()))
        .thenReturn(List.of(vendor("v-charak", "CHARAK PHARMA PVT. LTD."), vendor("v-hwc", "HIMALAYA WELLNESS")));
  }

  @Test
  void aDateRangeAloneIsQueriedInTheDatabaseOnShopDaysAndOrderedByBillDate() {
    when(repository.findByShopIdAndInvoiceDateInPeriod(
            eq(SHOP), any(Instant.class), any(Instant.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.list(
        SHOP, 2, 20, null, null, null, LocalDate.of(2025, 10, 25), LocalDate.of(2026, 3, 31));

    ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(repository)
        .findByShopIdAndInvoiceDateInPeriod(
            eq(SHOP), start.capture(), end.capture(), pageable.capture());
    // Midnight in India, and the whole of the last day.
    assertEquals(Instant.parse("2025-10-24T18:30:00Z"), start.getValue());
    assertEquals(Instant.parse("2026-03-31T18:30:00Z"), end.getValue());
    assertEquals(2, pageable.getValue().getPageNumber());
    assertEquals(
        Sort.by(Sort.Order.desc("invoiceDate"), Sort.Order.desc("id")),
        pageable.getValue().getSort());
    verify(repository, never()).findByShopId(eq(SHOP), any(Pageable.class));
  }

  @Test
  void anOpenEndedRangeStillReachesTheQuery() {
    when(repository.findByShopIdAndInvoiceDateInPeriod(
            eq(SHOP), any(Instant.class), any(Instant.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.list(SHOP, 0, 20, null, null, null, LocalDate.of(2025, 4, 1), null);

    verify(repository)
        .findByShopIdAndInvoiceDateInPeriod(
            eq(SHOP),
            eq(Instant.parse("2025-03-31T18:30:00Z")),
            any(Instant.class),
            any(Pageable.class));
  }

  @Test
  void withoutFiltersTheListIsStillNewestEnteredFirst() {
    when(repository.findByShopId(eq(SHOP), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    service.list(SHOP, 0, 20, null);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(repository).findByShopId(eq(SHOP), pageable.capture());
    assertEquals(
        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")),
        pageable.getValue().getSort());
  }

  @Test
  void aVendorAndARangeTogetherKeepOnlyThatVendorsBillsInsideTheShopDays() {
    when(repository.findByShopId(SHOP))
        .thenReturn(
            List.of(
                bill("a", "v-charak", "INV/PH/000830", "2025-11-13T00:00:00Z"),
                // 17:30 in India on the last day: inside.
                bill("b", "v-charak", "INV/PH/001257", "2026-03-31T12:00:00Z"),
                // Midnight in India on 1 April: outside.
                bill("c", "v-charak", "INV/PH/000001", "2026-03-31T18:30:00Z"),
                bill("d", "v-charak", "INV/PH/000017", "2025-04-07T00:00:00Z"),
                bill("e", "v-hwc", "5375100048", "2025-12-05T00:00:00Z"),
                bill("f", "v-charak", "NO-DATE", null)));

    VendorPurchaseInvoiceListResponse res =
        service.list(
            SHOP, 0, 20, null, null, "charak", LocalDate.of(2025, 10, 25), LocalDate.of(2026, 3, 31));

    assertEquals(List.of("INV/PH/001257", "INV/PH/000830"), invoiceNos(res));
    assertEquals(2, res.getPage().getTotalItems());
  }

  @Test
  void invoiceNumberAndVendorMustBothMatch() {
    when(repository.findByShopId(SHOP))
        .thenReturn(
            List.of(
                bill("a", "v-charak", "INV/PH/000830", "2025-11-13T00:00:00Z"),
                bill("b", "v-hwc", "INV/PH/000830", "2025-11-14T00:00:00Z"),
                bill("c", "v-charak", "INV/PH/000017", "2025-04-07T00:00:00Z")));

    VendorPurchaseInvoiceListResponse res =
        service.list(SHOP, 0, 20, null, "000830", "charak", null, null);

    assertEquals(List.of("INV/PH/000830"), invoiceNos(res));
    assertEquals("CHARAK PHARMA PVT. LTD.", res.getInvoices().get(0).getVendorName());
  }

  @Test
  void pagesAreCutAfterFiltering() {
    when(repository.findByShopId(SHOP))
        .thenReturn(
            List.of(
                bill("a", "v-charak", "C1", "2025-11-01T00:00:00Z"),
                bill("b", "v-charak", "C2", "2025-11-02T00:00:00Z"),
                bill("c", "v-hwc", "H1", "2025-11-03T00:00:00Z"),
                bill("d", "v-charak", "C3", "2025-11-04T00:00:00Z")));

    VendorPurchaseInvoiceListResponse res =
        service.list(SHOP, 1, 2, null, null, "charak", LocalDate.of(2025, 11, 1), null);

    assertEquals(List.of("C1"), invoiceNos(res));
    assertEquals(3, res.getPage().getTotalItems());
    assertEquals(2, res.getPage().getTotalPages());
  }

  @Test
  void aRangeThatEndsBeforeItStartsIsRefused() {
    assertThrows(
        ValidationException.class,
        () ->
            service.list(
                SHOP, 0, 20, null, null, null, LocalDate.of(2026, 3, 31), LocalDate.of(2025, 4, 1)));
  }

  @Test
  void anInvalidPatternIsRefused() {
    assertThrows(
        ValidationException.class, () -> service.list(SHOP, 0, 20, null, "(", null, null, null));
  }

  private static List<String> invoiceNos(VendorPurchaseInvoiceListResponse res) {
    return res.getInvoices().stream().map(VendorPurchaseInvoiceSummaryDto::getInvoiceNo).toList();
  }

  private static VendorPurchaseInvoice bill(
      String id, String vendorId, String invoiceNo, String invoiceDate) {
    VendorPurchaseInvoice inv = new VendorPurchaseInvoice();
    inv.setId(id);
    inv.setShopId(SHOP);
    inv.setVendorId(vendorId);
    inv.setInvoiceNo(invoiceNo);
    inv.setInvoiceDate(invoiceDate != null ? Instant.parse(invoiceDate) : null);
    inv.setCreatedAt(Instant.parse("2026-09-25T00:00:00Z"));
    return inv;
  }

  private static Vendor vendor(String id, String name) {
    Vendor v = new Vendor();
    v.setId(id);
    v.setName(name);
    return v;
  }
}
