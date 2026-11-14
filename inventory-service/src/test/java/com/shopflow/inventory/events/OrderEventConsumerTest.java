package com.shopflow.inventory.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import static org.awaitility.Awaitility.await;

import com.shopflow.inventory.InventoryIntegrationTest;
import com.shopflow.inventory.domain.StockLine;
import com.shopflow.inventory.domain.StockService;

/**
 * order.confirmed and order.shipped through a real Kafka broker into the reservation status, on the
 * shared application context. Each test uses its own reservation ids and event ids. A "sentinel" event
 * for the same order key is sent after the event under test: both go to one partition, so once the
 * sentinel is handled the earlier event has been consumed too.
 */
class OrderEventConsumerTest extends InventoryIntegrationTest {

@Autowired
KafkaTemplate<String, String> kafka;

@Autowired
StockService stock;

private String reservation(String sku) {
this.jdbc.sql("insert into stock (sku, available) values (?, 10) on conflict (sku) do nothing").param(sku).update();
String id = "res-" + UUID.randomUUID();
this.stock.reserve(id, List.of(new StockLine(sku, 1)));
return id;
}

private static String envelope(String eventId, String type, String payload) {
return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"payload\":" + payload
+ ",\"type\":\"" + type + "\"}";
}

private static String confirmedJson(String eventId, long orderId, @Nullable String reservationId) {
return envelope(eventId, "order.confirmed", "{\"orderId\":" + orderId + ",\"customerId\":\"c\",\"total\":1.5,"
+ "\"reservationId\":" + (reservationId == null ? "null" : "\"" + reservationId + "\"") + "}");
}

private static String shippedJson(String eventId, long orderId, String reservationId) {
return envelope(eventId, "order.shipped", "{\"orderId\":" + orderId + ",\"reservationId\":\"" + reservationId + "\"}");
}

private void send(long orderId, String json) {
this.kafka.send(OrderEvents.TOPIC, String.valueOf(orderId), json);
}

private String status(String reservationId) {
return this.jdbc.sql("select status from reservation where reservation_id = ?").param(reservationId)
.query(String.class).optional().orElse("(none)");
}

private int inboxRows(String eventId) {
return this.jdbc.sql("select count(*) from processed_events where consumer = 'inventory' and event_id = ?")
.param(eventId).query(Integer.class).single();
}

private int deadLetterRows(String eventId) {
return this.jdbc.sql("select count(*) from dead_letters where event_id = ?").param(eventId)
.query(Integer.class).single();
}

/** Sends an event for a fresh reservation after the one under test and waits until it is handled. */
private void awaitSentinel(long orderId) {
String sentinelReservation = reservation("EVT-SENTINEL");
String sentinelEvent = UUID.randomUUID().toString();
send(orderId, confirmedJson(sentinelEvent, orderId, sentinelReservation));
await().atMost(Duration.ofSeconds(30)).until(() -> status(sentinelReservation).equals("CONFIRMED"));
}

@Test
@DisplayName("order.confirmed moves a HELD reservation to CONFIRMED")
void confirmedEvent() {
String reservation = reservation("EVT-CONFIRM");
send(1001, confirmedJson(UUID.randomUUID().toString(), 1001, reservation));

await().atMost(Duration.ofSeconds(30)).until(() -> status(reservation).equals("CONFIRMED"));
}

@Test
@DisplayName("order.shipped moves a CONFIRMED reservation to SHIPPED")
void shippedEvent() {
String reservation = reservation("EVT-SHIP");
send(1002, confirmedJson(UUID.randomUUID().toString(), 1002, reservation));
await().atMost(Duration.ofSeconds(30)).until(() -> status(reservation).equals("CONFIRMED"));

send(1002, shippedJson(UUID.randomUUID().toString(), 1002, reservation));

await().atMost(Duration.ofSeconds(30)).until(() -> status(reservation).equals("SHIPPED"));
}

@Test
@DisplayName("the same event delivered twice leaves one inbox row and the same state")
void sameEventTwice() {
String reservation = reservation("EVT-TWICE");
String eventId = UUID.randomUUID().toString();
send(1003, confirmedJson(eventId, 1003, reservation));
send(1003, confirmedJson(eventId, 1003, reservation));
awaitSentinel(1003);

assertThat(status(reservation)).isEqualTo("CONFIRMED");
assertThat(inboxRows(eventId)).isEqualTo(1);
assertThat(deadLetterRows(eventId)).isZero();
}

@Test
@DisplayName("shipped before confirmed stays SHIPPED")
void shippedBeforeConfirmed() {
String reservation = reservation("EVT-ORDER");
send(1004, shippedJson(UUID.randomUUID().toString(), 1004, reservation));
await().atMost(Duration.ofSeconds(30)).until(() -> status(reservation).equals("SHIPPED"));

send(1004, confirmedJson(UUID.randomUUID().toString(), 1004, reservation));
awaitSentinel(1004);

assertThat(status(reservation)).isEqualTo("SHIPPED");
}

@Test
@DisplayName("unknown type, unknown reservation and missing reservation id are skipped without a dead letter")
void skippedEventsDoNotReachTheDeadLetterTable() {
String unknownType = UUID.randomUUID().toString();
String unknownReservation = UUID.randomUUID().toString();
String noReservation = UUID.randomUUID().toString();
send(1005, envelope(unknownType, "order.refunded", "{\"orderId\":1005,\"amount\":3}"));
send(1005, confirmedJson(unknownReservation, 1005, "no-such-reservation"));
send(1005, confirmedJson(noReservation, 1005, null));
awaitSentinel(1005);

// A failing handler would be retried after 0.5 s, 1 s and 2 s and then dead-lettered: watch past that.
await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(10))
.until(() -> deadLetterRows(unknownType) + deadLetterRows(unknownReservation) + deadLetterRows(noReservation) == 0);
assertThat(inboxRows(unknownType)).as("unknown type leaves no inbox row").isZero();
assertThat(inboxRows(noReservation)).as("event without reservation id leaves no inbox row").isZero();
assertThat(status("no-such-reservation")).isEqualTo("(none)");
}

@Test
@DisplayName("an event that keeps failing is retried, then stored as a dead letter, and later events still flow")
void poisonEventReachesTheDeadLetters() {
String poison = UUID.randomUUID().toString();
// PostgreSQL rejects a NUL character in text, so handling this event fails every time.
send(1006, confirmedJson(poison, 1006, "bad\\u0000id"));

await().atMost(Duration.ofSeconds(60)).until(() -> deadLetterRows(poison) == 1);
assertThat(inboxRows(poison)).as("the failed attempts rolled their inbox row back").isZero();
awaitSentinel(1006);
}
}