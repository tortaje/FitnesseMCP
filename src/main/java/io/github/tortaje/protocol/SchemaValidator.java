package io.github.tortaje.protocol;

import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal JSON Schema check: {@code required} plus the top-level {@code type} of each property. */
public final class SchemaValidator {
  private SchemaValidator() {}

  /** Returns a description of the first violation, or empty when the arguments are valid. */
  public static Optional<String> validate(JSONObject schema, JSONObject args) {
    JSONArray required = schema.optJSONArray("required");
    if (required != null) {
      for (int i = 0; i < required.length(); i++) {
        String name = required.getString(i);
        if (!args.has(name) || args.isNull(name)) {
          return Optional.of("Missing required argument '" + name + "'.");
        }
      }
    }
    JSONObject properties = schema.optJSONObject("properties");
    if (properties != null) {
      for (String name : properties.keySet()) {
        if (!args.has(name) || args.isNull(name)) continue;
        String type = properties.getJSONObject(name).optString("type", "");
        if (!type.isEmpty() && !matches(type, args.get(name))) {
          return Optional.of("Argument '" + name + "' must be of type " + type + ".");
        }
      }
    }
    return Optional.empty();
  }

  private static boolean matches(String type, Object value) {
    switch (type) {
      case "string":
        return value instanceof String;
      case "boolean":
        return value instanceof Boolean;
      case "array":
        return value instanceof JSONArray;
      case "object":
        return value instanceof JSONObject;
      case "integer":
        if (value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger) {
          return true;
        }
        if (value instanceof Double || value instanceof Float) {
          double d = ((Number) value).doubleValue();
          return d == Math.rint(d) && !Double.isInfinite(d);
        }
        return value instanceof java.math.BigDecimal
            && ((java.math.BigDecimal) value).stripTrailingZeros().scale() <= 0;
      default:
        return true;
    }
  }
}
