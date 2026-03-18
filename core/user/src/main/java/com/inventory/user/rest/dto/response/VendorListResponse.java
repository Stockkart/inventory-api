package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorListResponse {
  private List<VendorDto> data;
  private int page;
  private int limit;
  private long total;
  private int totalPages;
}
