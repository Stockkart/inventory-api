package com.inventory.taxation.gstin;

/** The GST network (or the provider in front of it) could not answer. Callers treat it as "unknown for now". */
public class GstinLookupException extends RuntimeException {
  public GstinLookupException(String message) {
    super(message);
  }

  public GstinLookupException(String message, Throwable cause) {
    super(message, cause);
  }
}
