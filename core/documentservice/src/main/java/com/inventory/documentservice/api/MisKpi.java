package com.inventory.documentservice.api;

import lombok.Builder;

/** One label/value pair printed above a MIS report's table, e.g. ("Net sales", "1,250.00"). */
@Builder
public record MisKpi(String label, String value) {}
