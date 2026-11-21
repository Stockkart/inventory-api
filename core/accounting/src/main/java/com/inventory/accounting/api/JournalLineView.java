package com.inventory.accounting.api;

import java.math.BigDecimal;

/**
 * One line of a {@link JournalEntryView}.
 *
 * @param accountCode chart-of-accounts code the line posts to
 * @param debit debit amount; null when the line is a credit
 * @param credit credit amount; null when the line is a debit
 */
public record JournalLineView(String accountCode, BigDecimal debit, BigDecimal credit) {}
