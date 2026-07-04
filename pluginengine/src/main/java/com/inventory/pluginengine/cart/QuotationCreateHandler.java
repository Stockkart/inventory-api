package com.inventory.pluginengine.cart;

/** Optional hook when a new open quotation is created (e.g. cafe order token). */
public interface QuotationCreateHandler {

  String getVerticalId();

  QuotationCreateResult onQuotationCreated(QuotationCreateContext context);
}
