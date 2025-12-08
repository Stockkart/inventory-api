package com.inventory.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
  private boolean success;
  private T data;
  private String message;
  private String error;

  public static <T> ApiResponse<T> success(T data) {
    ApiResponse<T> response = new ApiResponse<>();
    response.setSuccess(true);
    response.setData(data);
    return response;
  }

  public static <T> ApiResponse<T> success(T data, String message) {
    ApiResponse<T> response = new ApiResponse<>();
    response.setSuccess(true);
    response.setData(data);
    response.setMessage(message);
    return response;
  }

  public static <T> ApiResponse<T> error(String error) {
    ApiResponse<T> response = new ApiResponse<>();
    response.setSuccess(false);
    response.setError(error);
    return response;
  }
}

