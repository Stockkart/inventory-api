package com.inventory.user.rest.dto.invitation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvitationListResponse {
  private List<InvitationDto> data;
}

