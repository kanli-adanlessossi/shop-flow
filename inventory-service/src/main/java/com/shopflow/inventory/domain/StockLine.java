package com.shopflow.inventory.domain;

/** One line of a reservation request: how many units of which SKU. */
public record StockLine(String sku, int quantity) {
}
