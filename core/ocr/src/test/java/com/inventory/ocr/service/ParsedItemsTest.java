package com.inventory.ocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.ocr.api.ParsedInvoiceItem;
import com.inventory.ocr.api.ParsedReminder;
import com.inventory.ocr.dto.ParsedInventoryItem;
import com.inventory.ocr.dto.ParsedReminderDto;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ParsedItemsTest {

  @Test
  void theRecordHasExactlyTheWorkingTypesFields() {
    Set<String> working = Arrays.stream(ParsedInventoryItem.class.getDeclaredFields())
        .filter(f -> !Modifier.isStatic(f.getModifiers()))
        .map(Field::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    Set<String> api = Arrays.stream(ParsedInvoiceItem.class.getRecordComponents())
        .map(RecordComponent::getName)
        .collect(Collectors.toCollection(TreeSet::new));
    assertEquals(working, api);
  }

  @Test
  void everyFieldIsCopied() throws Exception {
    ParsedInventoryItem in = new ParsedInventoryItem();
    int n = 0;
    // A distinct value in every field, so a field copied into the wrong place also fails.
    for (Field f : ParsedInventoryItem.class.getDeclaredFields()) {
      if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("customReminders")) {
        continue;
      }
      f.setAccessible(true);
      n++;
      Class<?> t = f.getType();
      if (t == String.class) {
        f.set(in, "v-" + f.getName());
      } else if (t == Integer.class) {
        f.set(in, 100 + n);
      } else if (t == BigDecimal.class) {
        f.set(in, new BigDecimal(n + ".25"));
      } else {
        throw new AssertionError("add a sample value for " + t + " " + f.getName());
      }
    }
    in.setCustomReminders(new ArrayList<>(List.of(
        new ParsedReminderDto("2027-01-01T00:00:00Z", "2027-02-01T00:00:00Z", "first"))));

    ParsedInvoiceItem out = ParsedItems.toApi(in);

    for (RecordComponent c : ParsedInvoiceItem.class.getRecordComponents()) {
      if (c.getName().equals("customReminders")) {
        continue;
      }
      Field f = ParsedInventoryItem.class.getDeclaredField(c.getName());
      f.setAccessible(true);
      assertEquals(f.get(in), c.getAccessor().invoke(out), c.getName());
    }
    assertEquals(List.of(new ParsedReminder("2027-01-01T00:00:00Z", "2027-02-01T00:00:00Z", "first")),
        out.customReminders());
  }

  @Test
  void missingRemindersBecomeAnEmptyListAndNullEntriesStayNull() {
    ParsedInventoryItem in = new ParsedInventoryItem();
    ParsedInvoiceItem out = ParsedItems.toApi(in);
    assertNotNull(out.customReminders());
    assertTrue(out.customReminders().isEmpty());

    List<ParsedInventoryItem> withNull = new ArrayList<>();
    withNull.add(null);
    withNull.add(in);
    List<ParsedInvoiceItem> mapped = ParsedItems.toApi(withNull);
    assertEquals(2, mapped.size());
    assertNull(mapped.get(0));
    assertNull(ParsedItems.toApi((List<ParsedInventoryItem>) null));
  }
}
