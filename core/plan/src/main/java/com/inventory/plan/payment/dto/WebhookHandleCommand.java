package com.inventory.plan.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class WebhookHandleCommand {
  private String rawBody;
  private Map<String, String> headers;
}
