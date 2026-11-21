package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Name and contact details of one login, as other modules (HR "add employee from a login")
 * need them to prefill a form. Holds PII: callers decide who may see it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginContactDto {
  private String userId;
  private String name;
  private String email;
  private String phone;
}
