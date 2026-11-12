package com.shopflow.order.inventory;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Builds the {@link InventoryClient} proxy on a {@link RestClient} created from Boot's
 * auto-configured {@link RestClient.Builder}, so message converters and any
 * {@code RestClientCustomizer} beans apply. Timeouts are specific to this client.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryClientConfig {

	@Bean
	InventoryClient inventoryClient(RestClient.Builder builder, InventoryProperties properties,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder, HttpClientSettings sharedSettings) {
		// Start from Boot's shared settings (spring.http.clients.*: cookie handling, redirects,
		// SSL bundle and any InetAddressFilter bean), then apply this client's own timeouts.
		HttpClientSettings settings = sharedSettings
				.withTimeouts(properties.connectTimeout(), properties.readTimeout());
		RestClient restClient = builder
				.baseUrl(properties.baseUrl().toString())
				.requestInterceptor(new CorrelationIdInterceptor())
				.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
					throw toException(request, response.getStatusCode());
				})
				.requestFactory(requestFactoryBuilder.build(settings))
				.build();
		return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
				.build()
				.createClient(InventoryClient.class);
	}

	/** Turns an error status into the matching typed exception. */
	static InventoryException toException(HttpRequest request, HttpStatusCode statusCode) {
		int status = statusCode.value();
		String message = "Inventory answered " + status + " to " + request.getMethod() + " " + request.getURI().getPath();
		if (status == HttpStatus.NOT_FOUND.value()) {
			return new InventoryException.NotFound(message, status);
		}
		if (status == HttpStatus.CONFLICT.value()) {
			return new InventoryException.Conflict(message, status);
		}
		if (statusCode.is5xxServerError()) {
			return new InventoryException.ServerError(message, status);
		}
		return new InventoryException.BadRequest(message, status);
	}

}
