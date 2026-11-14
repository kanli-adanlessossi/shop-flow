package com.shopflow.order.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sales for one day (UTC). Column aliases in the query map to these components
 * by name, with snake_case matched to camelCase.
 */
public record DailySales(LocalDate salesDate, long orderCount, long unitCount, BigDecimal revenue) {
}
