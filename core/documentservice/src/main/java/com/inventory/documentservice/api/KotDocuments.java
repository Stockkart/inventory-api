package com.inventory.documentservice.api;

/** Renders kitchen order tickets. */
public interface KotDocuments {

  /** @return the ticket as a 3-inch thermal PDF */
  byte[] pdf(KotDocument document);
}
