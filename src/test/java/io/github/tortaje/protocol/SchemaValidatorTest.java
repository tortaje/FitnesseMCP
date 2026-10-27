package io.github.tortaje.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SchemaValidatorTest {
  private final JSONObject schema = new JSONObject()
      .put("type", "object")
      .put("properties", new JSONObject()
          .put("name", new JSONObject().put("type", "string"))
          .put("count", new JSONObject().put("type", "integer"))
          .put("flag", new JSONObject().put("type", "boolean"))
          .put("items", new JSONObject().put("type", "array"))
          .put("opts", new JSONObject().put("type", "object")))
      .put("required", new JSONArray().put("name"));

  @Test void missingRequiredNamesTheProperty() {
    assertThat(SchemaValidator.validate(schema, new JSONObject()))
        .hasValueSatisfying(m -> assertThat(m).contains("name"));
  }

  @Test void wrongTypeIsReported() {
    JSONObject args = new JSONObject().put("name", "a").put("count", "3");
    assertThat(SchemaValidator.validate(schema, args))
        .hasValueSatisfying(m -> assertThat(m).contains("count").contains("integer"));
  }

  @Test void unknownExtraPropertiesAreAccepted() {
    JSONObject args = new JSONObject().put("name", "a").put("extra", 1);
    assertThat(SchemaValidator.validate(schema, args)).isEmpty();
  }

  @Test void validArgumentsPass() {
    JSONObject args = new JSONObject().put("name", "a").put("count", 3).put("flag", true)
        .put("items", new JSONArray()).put("opts", new JSONObject());
    assertThat(SchemaValidator.validate(schema, args)).isEmpty();
  }

  @Test void nonIntegralNumberIsNotAnInteger() {
    JSONObject args = new JSONObject().put("name", "a").put("count", 1.5);
    assertThat(SchemaValidator.validate(schema, args)).isPresent();
  }
}
