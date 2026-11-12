package com.shopflow.order.domain;

/** Thrown when a list is asked to sort by something it cannot sort by. */
public class InvalidSortException extends RuntimeException {

	private final String property;

	public InvalidSortException(String property) {
		super("Unknown sort property '" + property + "'.");
		this.property = property;
	}

	public String getProperty() {
		return this.property;
	}

}
