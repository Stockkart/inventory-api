package com.inventory.documentservice.domain;

/**
 * A document rendered for a dot-matrix printer: the text, what kind of document it is, and the
 * number printed on it (invoice, estimate or note number), which names the downloaded file.
 */
public record DotMatrixDocument(String text, DotMatrixDocumentKind kind, String documentNumber) {}
