package com.shopflow.order.inventory;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
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
 *
 * <p>Every request carries a Bearer token that this service fetches from Keycloak with the client-credentials
 * grant (registration {@value #REGISTRATION_ID} in {@code spring.security.oauth2.client}). The token is cached
 * by the {@link OAuth2AuthorizedClientManager} until shortly before it expires, so there is no token request
 * per call.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryClientConfig {

	/** The {@code spring.security.oauth2.client.registration} entry used to sign in at inventory-service. */
	static final String REGISTRATION_ID = "inventory";

	/**
	 * Not tied to a user or request: the token belongs to this service. A fixed principal keeps one cache entry
	 * for every thread, including the background release that has no security context.
	 */
	private static final AnonymousAuthenticationToken SERVICE_PRINCIPAL = new AnonymousAuthenticationToken(
			REGISTRATION_ID, "order-platform", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	@Bean
	OAuth2AuthorizedClientManager inventoryAuthorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientService clients) {
		AuthorizedClientServiceOAuth2AuthorizedClientManager manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
				registrations, clients);
		manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
		return manager;
	}

	@Bean
	InventoryClient inventoryClient(RestClient.Builder builder, InventoryProperties properties,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder, HttpClientSettings sharedSettings,
			OAuth2AuthorizedClientManager authorizedClientManager) {
		// Start from Boot's shared settings (spring.http.clients.*: cookie handling, redirects,
		// SSL bundle and any InetAddressFilter bean), then apply this client's own timeouts.
		HttpClientSettings settings = sharedSettings
				.withTimeouts(properties.connectTimeout(), properties.readTimeout());
		OAuth2ClientHttpRequestInterceptor tokens = new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
		tokens.setClientRegistrationIdResolver((request) -> REGISTRATION_ID);
		tokens.setPrincipalResolver((request) -> SERVICE_PRINCIPAL);
		RestClient restClient = builder
				.baseUrl(properties.baseUrl().toString())
				.requestInterceptor(new CorrelationIdInterceptor())
				.requestInterceptor(tokens)
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
