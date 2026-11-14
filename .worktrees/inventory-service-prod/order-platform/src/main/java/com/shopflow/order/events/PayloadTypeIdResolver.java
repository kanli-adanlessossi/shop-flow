package com.shopflow.order.events;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import tools.jackson.databind.DatabindContext;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.jsontype.impl.TypeIdResolverBase;

/**
 * Maps the {@code type} of an {@link EventEnvelope} to a {@link Payload} class and back.
 *
 * <p>The names come from {@code @JsonTypeName} on the classes {@link Payload} permits, so adding a
 * payload means adding the class and its annotation, nothing else. Reading follows two rules that
 * the standard Jackson type handling cannot express together:
 * <ul>
 * <li>a type name nobody here knows (for example from a newer producer) becomes an
 * {@link UnknownPayload}, which consumers skip;</li>
 * <li>a missing, null or blank type is not an event at all and fails, so the message is rejected
 * like any other malformed one.</li>
 * </ul>
 */
public class PayloadTypeIdResolver extends TypeIdResolverBase {

	private static final Map<String, Class<? extends Payload>> BY_NAME = byName();

	@SuppressWarnings("unchecked")
	private static Map<String, Class<? extends Payload>> byName() {
		Map<String, Class<? extends Payload>> names = new HashMap<>();
		for (Class<?> type : Payload.class.getPermittedSubclasses()) {
			JsonTypeName name = type.getAnnotation(JsonTypeName.class);
			if (name != null) { // UnknownPayload has none on purpose
				names.put(name.value(), (Class<? extends Payload>) type);
			}
		}
		return Map.copyOf(names);
	}

	@Override
	public String idFromValue(DatabindContext context, Object value) {
		return ((Payload) value).type();
	}

	@Override
	public String idFromValueAndType(DatabindContext context, Object value, Class<?> suggestedType) {
		return idFromValue(context, value);
	}

	@Override
	public JavaType typeFromId(DatabindContext context, String id) {
		if (id == null || id.isBlank()) {
			throw ((DeserializationContext) context).invalidTypeIdException(this._baseType, String.valueOf(id),
					"the event type is missing or blank");
		}
		return context.constructType(BY_NAME.getOrDefault(id, UnknownPayload.class));
	}

	@Override
	public String getDescForKnownTypeIds() {
		return String.join(", ", BY_NAME.keySet());
	}

	@Override
	public JsonTypeInfo.Id getMechanism() {
		return JsonTypeInfo.Id.CUSTOM;
	}

}
