package com.inventory.user.rest.dto.joinrequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequestListResponse {
  private List<JoinRequestDto> data;
}

