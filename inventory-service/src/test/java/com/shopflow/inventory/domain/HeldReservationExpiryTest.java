package com.shopflow.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.shopflow.inventory.InventoryIntegrationTest;

/**
 * The stale-hold job on the shared context. The tests back-date {@code created_at} in the database and call
 * the job directly, so no second context with a fixed clock is needed.
 */
class HeldReservationExpiryTest extends InventoryIntegrationTest {

@Autowired
HeldReservationExpiry expiry;

@Autowired
StockService stock;

private String sku() {
String sku = "EXP-" + UUID.randomUUID();
this.jdbc.sql("insert into stock (sku, available) values (?, 10)").param(sku).update();
return sku;
}

private String reserve(String sku, int quantity, String status, String age) {
String id = "exp-" + UUID.randomUUID();
this.stock.reserve(id, List.of(new StockLine(sku, quantity)));
this.jdbc.sql("update reservation set status = ?, created_at = now() - cast(? as interval) where reservation_id = ?")
.params(status, age, id).update();
return id;
}

private int available(String sku) {
return this.stock.available(sku);
}

private boolean exists(String reservationId) {
return this.jdbc.sql("select count(*) from reservation where reservation_id = ?").param(reservationId)
.query(Integer.class).single() == 1;
}

@Test
@DisplayName("a HELD reservation older than the hold TTL is released and its stock returned")
void oldHeldIsReleased() {
String sku = sku();
String old = reserve(sku, 3, "HELD", "2 hours");
assertThat(available(sku)).isEqualTo(7);

this.expiry.expire();

assertThat(exists(old)).isFalse();
assertThat(available(sku)).isEqualTo(10);
}

@Test
@DisplayName("a young HELD reservation is kept")
void youngHeldIsKept() {
String sku = sku();
String young = reserve(sku, 3, "HELD", "10 minutes");

this.expiry.expire();

assertThat(exists(young)).isTrue();
assertThat(available(sku)).isEqualTo(7);
}

@Test
@DisplayName("old CONFIRMED and SHIPPED reservations are never touched")
void confirmedAndShippedNeverExpire() {
String sku = sku();
String confirmed = reserve(sku, 2, "CONFIRMED", "5 days");
String shipped = reserve(sku, 1, "SHIPPED", "5 days");

this.expiry.expire();

assertThat(exists(confirmed)).isTrue();
assertThat(exists(shipped)).isTrue();
assertThat(available(sku)).isEqualTo(7);
}

@Test
@DisplayName("a reservation that is confirmed after the job selected it is not released")
void confirmedInBetweenIsKept() {
String sku = sku();
String id = reserve(sku, 2, "CONFIRMED", "2 hours");

boolean released = this.stock.releaseIfStillHeld(id, java.time.Instant.now());

assertThat(released).isFalse();
assertThat(exists(id)).isTrue();
}
}