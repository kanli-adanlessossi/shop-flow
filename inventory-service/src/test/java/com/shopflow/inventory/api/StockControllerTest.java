package com.shopflow.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.shopflow.inventory.domain.InsufficientStockException;
import com.shopflow.inventory.domain.ReservationNotFoundException;
import com.shopflow.inventory.domain.ReservationShippedException;
import com.shopflow.inventory.domain.StockLine;
import com.shopflow.inventory.domain.StockService;
import com.shopflow.inventory.domain.UnknownSkuException;
import com.shopflow.inventory.infra.SecurityConfig;

/** The HTTP contract of {@link StockController}: status codes, bodies, validation and the role check. */
@WebMvcTest(StockController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class StockControllerTest {

	private static final String RESERVE_JSON = """
			{"reservationId": "r-1", "lines": [{"sku": "SKU-001", "quantity": 2}]}""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	StockService stock;

	// Keeps the test away from Keycloak: the resource server needs a decoder bean, but jwt() skips it.
	@MockitoBean
	JwtDecoder jwtDecoder;

	private static JwtRequestPostProcessor inventoryRole() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_INVENTORY"));
	}

	@Test
	void getStockReturnsTheAvailableUnits() throws Exception {
		given(this.stock.available("SKU-001")).willReturn(7);
		this.mvc.perform(get("/api/stock/SKU-001").with(inventoryRole()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sku").value("SKU-001"))
				.andExpect(jsonPath("$.available").value(7))
				.andExpect(header().exists("X-Correlation-Id"));
	}

	@Test
	void getUnknownSkuIsNotFound() throws Exception {
		given(this.stock.available("NOPE")).willThrow(new UnknownSkuException("NOPE"));
		this.mvc.perform(get("/api/stock/NOPE").with(inventoryRole()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Unknown SKU"));
	}

	@Test
	void reserveAnswersWithTheReservationId() throws Exception {
		this.mvc.perform(post("/api/stock/reservations").with(inventoryRole())
				.contentType(MediaType.APPLICATION_JSON).content(RESERVE_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservationId").value("r-1"));
		verify(this.stock).reserve("r-1", List.of(new StockLine("SKU-001", 2)));
	}

	@Test
	void reserveWithTooLittleStockIsAConflictProblem() throws Exception {
		willThrow(new InsufficientStockException("SKU-001")).given(this.stock).reserve(any(), any());
		this.mvc.perform(post("/api/stock/reservations").with(inventoryRole())
				.contentType(MediaType.APPLICATION_JSON).content(RESERVE_JSON))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Insufficient stock"))
				.andExpect(jsonPath("$.sku").value("SKU-001"));
	}

	@Test
	void invalidBodyIsABadRequestProblemWithFieldErrors() throws Exception {
		String invalid = """
				{"reservationId": "", "lines": [{"sku": "SKU-001", "quantity": 0}]}""";
		this.mvc.perform(post("/api/stock/reservations").with(inventoryRole())
				.contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors").isArray())
				.andExpect(jsonPath("$.errors.length()").value(2));
		verifyNoInteractions(this.stock);
	}

	@Test
	void emptyLinesAreABadRequest() throws Exception {
		this.mvc.perform(post("/api/stock/reservations").with(inventoryRole())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reservationId\": \"r-1\", \"lines\": []}"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(this.stock);
	}

	@Test
	void malformedJsonIsABadRequest() throws Exception {
		this.mvc.perform(post("/api/stock/reservations").with(inventoryRole())
				.contentType(MediaType.APPLICATION_JSON).content("{nope"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void releaseAnswersNoContent() throws Exception {
		this.mvc.perform(delete("/api/stock/reservations/r-1").with(inventoryRole()))
				.andExpect(status().isNoContent());
		verify(this.stock).release("r-1");
	}

	@Test
	void releaseOfAnUnknownReservationIsNotFound() throws Exception {
		willThrow(new ReservationNotFoundException("r-1")).given(this.stock).release("r-1");
		this.mvc.perform(delete("/api/stock/reservations/r-1").with(inventoryRole()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Reservation not found"));
	}

	@Test
	void releaseOfAShippedReservationIsAConflict() throws Exception {
		willThrow(new ReservationShippedException("r-1")).given(this.stock).release("r-1");
		this.mvc.perform(delete("/api/stock/reservations/r-1").with(inventoryRole()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Reservation already shipped"));
	}

	@Test
	void withoutATokenTheAnswerIs401() throws Exception {
		this.mvc.perform(get("/api/stock/SKU-001")).andExpect(status().isUnauthorized());
		verifyNoInteractions(this.stock);
	}

	@Test
	void aTokenWithoutTheInventoryRoleIs403() throws Exception {
		this.mvc.perform(get("/api/stock/SKU-001").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
				.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/stock/reservations").with(jwt())
				.contentType(MediaType.APPLICATION_JSON).content(RESERVE_JSON))
				.andExpect(status().isForbidden());
		verifyNoInteractions(this.stock);
	}

}
