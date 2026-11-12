-- The status for a payment confirmed by the provider is now called CONFIRMED (it was PAID).
update orders set status = 'CONFIRMED' where status = 'PAID';
