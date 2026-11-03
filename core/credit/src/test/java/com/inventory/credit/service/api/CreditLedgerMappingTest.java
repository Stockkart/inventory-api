package com.inventory.credit.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.credit.api.CreditBalanceStatus;
import com.inventory.credit.api.CreditCharge;
import com.inventory.credit.api.CreditDirection;
import com.inventory.credit.api.CreditEntryType;
import com.inventory.credit.api.CreditPartyType;
import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.request.CreateCreditChargeRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The api records are copied into and out of the credit module's own classes. These tests check
 * the copies are complete and exact: the record and the class have the same field names, and
 * every value arrives unchanged.
 */
class CreditLedgerMappingTest {

  @Test
  void chargeIsCopiedFieldForField() {
    CreditCharge charge =
        CreditCharge.builder()
            .partyType(CreditPartyType.VENDOR)
            .partyId("party")
            .partyDisplayName("name")
            .partyPhone("phone")
            .amount(new BigDecimal("12.50"))
            .note("note")
            .referenceType("PURCHASE")
            .referenceId("ref")
            .sourceKey("PURCHASE:CREDIT:ref")
            .txnDate(LocalDate.of(2026, 1, 2))
            .build();

    assertSameData(charge, CreditLedgerImpl.toRequest(charge));
  }

  @Test
  void entryIsCopiedFieldForField() {
    CreditEntry e =
        new CreditEntry(
            "id",
            "txn",
            "shop",
            "account",
            CreditPartyType.CUSTOMER,
            "party",
            CreditEntryType.SETTLEMENT,
            CreditDirection.DECREASE_DUE,
            new BigDecimal("1"),
            new BigDecimal("2"),
            "note",
            "SALE",
            "ref",
            "key",
            "UPI",
            "bank",
            LocalDate.of(2026, 3, 4),
            "user",
            Instant.parse("2026-03-04T05:06:07Z"));

    assertSameData(CreditLedgerImpl.toView(e), e);
    assertNull(CreditLedgerImpl.toView((CreditEntry) null));
  }

  @Test
  void accountIsCopiedFieldForField() {
    CreditAccount a =
        new CreditAccount(
            "id",
            "shop",
            CreditPartyType.VENDOR,
            "party",
            "name",
            "phone",
            new BigDecimal("3"),
            CreditBalanceStatus.values()[0],
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-02T00:00:00Z"),
            Instant.parse("2026-01-03T00:00:00Z"));

    assertSameData(CreditLedgerImpl.toView(a), a);
    assertNull(CreditLedgerImpl.toView((CreditAccount) null));
  }

  /** Same field names on both sides, every value set and equal. */
  private static void assertSameData(Record record, Object bean) {
    RecordComponent[] components = record.getClass().getRecordComponents();
    Set<String> componentNames =
        Arrays.stream(components)
            .map(RecordComponent::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> beanFields =
        Arrays.stream(bean.getClass().getDeclaredFields())
            .filter(f -> !Modifier.isStatic(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(beanFields, componentNames, bean.getClass().getSimpleName() + " fields");
    for (RecordComponent c : components) {
      try {
        Object value = c.getAccessor().invoke(record);
        assertNotNull(value, c.getName() + " should be set in the test data");
        Field f = bean.getClass().getDeclaredField(c.getName());
        f.setAccessible(true);
        assertEquals(value, f.get(bean), c.getName());
      } catch (ReflectiveOperationException ex) {
        throw new AssertionError(c.getName(), ex);
      }
    }
  }
}
