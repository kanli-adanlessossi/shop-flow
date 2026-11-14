package com.shopflow.order.api;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.shopflow.order.domain.OrderNotFoundException;
import com.shopflow.order.domain.OrderService;

/**
 * Ownership rule for {@code @PreAuthorize}, used as {@code @orderAccess.isOwner(#id, authentication)}.
 *
 * <p>An order belongs to the user whose name equals the order's {@code customerId}. The name is the
 * JWT claim set by {@code spring.security.oauth2.resourceserver.jwt.principal-claim-name}
 * (the Keycloak {@code preferred_username}, for example {@code alice}). Admins are let through by
 * the callers of this bean, not here, so this answers only "is it theirs".
 */
@Component("orderAccess")
public class OrderAccess {

	private final OrderService orders;

	public OrderAccess(OrderService orders) {
		this.orders = orders;
	}

	/**
	 * True if the order exists and belongs to the caller. True as well when the order does not exist,
	 * so the request goes on and gets its normal 404 instead of a 403; there is nothing to protect.
	 * Accepted trade-off: 404 for a missing id and 403 for someone else's lets a signed-in user tell
	 * which order ids exist (not what is in them). docs/user-stories.md asks for exactly these two answers,
	 * and ids are sequential numbers anyway. Answer 404 for both here if ids ever become sensitive.
	 * The read uses the order cache, so a check is normally no extra database query.
	 */
	public boolean isOwner(long orderId, Authentication authentication) {
		try {
			return this.orders.findById(orderId).customerId().equals(authentication.getName());
		}
		catch (OrderNotFoundException ex) {
			return true;
		}
	}

}
