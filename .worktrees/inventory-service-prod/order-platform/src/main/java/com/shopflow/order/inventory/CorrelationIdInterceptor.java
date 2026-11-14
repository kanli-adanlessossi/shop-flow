package com.shopflow.order.inventory;

import java.io.IOException;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import com.shopflow.order.infra.CorrelationId;

/** Forwards the current request's correlation id to inventory-service. */
class CorrelationIdInterceptor implements ClientHttpRequestInterceptor {

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		request.getHeaders().set(CorrelationId.HEADER, CorrelationId.currentOrNew());
		return execution.execute(request, body);
	}

}
