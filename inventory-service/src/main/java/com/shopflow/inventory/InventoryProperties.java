package com.shopflow.inventory;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * The {@code shopflow.inventory.*} properties.
 *
 * @param holdTtl how long a HELD reservation may wait for its order to be paid before the expiry job
 * releases it. Keep it above order-platform's {@code shopflow.cancel-after} (30m): the order is then
 * cancelled, and its reservation released, before this job would act.
 * @param processedEventsRetention how long the inbox remembers handled event ids; keep it above the topic retention
 */
@ConfigurationProperties("shopflow.inventory")
@Validated
public record InventoryProperties(
@DefaultValue("45m") @NotNull Duration holdTtl,
@DefaultValue("14d") @NotNull Duration processedEventsRetention) {
}