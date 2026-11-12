package com.shopflow.order.events;

/**
 * Tells a customer about an order event (mail, SMS, push, ...). There is no real channel in the
 * project yet; see {@link LoggingNotificationSender}.
 *
 * <p>Implementations talk to something outside the database, which a transaction cannot undo, so
 * the inbox alone gives "at least once" for them. Pass {@code idempotencyKey} (the event id) to the
 * provider when it supports one, so a repeat caused by a failure after the send is not delivered
 * twice.
 */
public interface NotificationSender {

	void send(String idempotencyKey, Payload payload);

}
