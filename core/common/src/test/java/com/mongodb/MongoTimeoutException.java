package com.mongodb;

/**
 * Test stand-in with the driver's class name: core/common does not depend on the MongoDB driver,
 * and {@code GlobalExceptionHandler} recognises the timeout by name.
 */
public class MongoTimeoutException extends RuntimeException {
  public MongoTimeoutException(String message) {
    super(message);
  }
}
