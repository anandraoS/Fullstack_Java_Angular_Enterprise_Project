package com.enterprise.oms.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;

/**
 * Fluent JSON assertions over JSON Pointer paths, with OMS-specific shortcuts.
 * <pre>{@code
 * JsonAssertions.of(body).isProblem(404, "NOT_FOUND").hasText("/instance", "/api/v1/orders/x");
 * JsonAssertions.of(body).isPage(20, 0).hasInt("/totalElements", 3);
 * }</pre>
 */
public final class JsonAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode root;

    private JsonAssertions(JsonNode root) {
        this.root = root;
    }

    public static JsonAssertions of(String json) {
        try {
            return new JsonAssertions(MAPPER.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("Not valid JSON: " + json, e);
        }
    }

    public JsonNode node(String pointer) {
        JsonNode n = root.at(pointer);
        Assertions.assertThat(n.isMissingNode()).as("JSON path %s present in %s", pointer, root).isFalse();
        return n;
    }

    public JsonAssertions hasText(String pointer, String expected) {
        Assertions.assertThat(node(pointer).asText()).as(pointer).isEqualTo(expected);
        return this;
    }

    public JsonAssertions hasInt(String pointer, int expected) {
        Assertions.assertThat(node(pointer).asInt()).as(pointer).isEqualTo(expected);
        return this;
    }

    public JsonAssertions hasBoolean(String pointer, boolean expected) {
        Assertions.assertThat(node(pointer).asBoolean()).as(pointer).isEqualTo(expected);
        return this;
    }

    public JsonAssertions hasField(String pointer) {
        node(pointer);
        return this;
    }

    public JsonAssertions hasNoField(String pointer) {
        Assertions.assertThat(root.at(pointer).isMissingNode()).as("JSON path %s absent in %s", pointer, root).isTrue();
        return this;
    }

    public JsonAssertions isNull(String pointer) {
        Assertions.assertThat(node(pointer).isNull()).as(pointer + " is null").isTrue();
        return this;
    }

    public JsonAssertions arraySize(String pointer, int size) {
        JsonNode n = node(pointer);
        Assertions.assertThat(n.isArray()).as(pointer + " is array").isTrue();
        Assertions.assertThat(n.size()).as(pointer + " size").isEqualTo(size);
        return this;
    }

    /** RFC 7807 problem in the OMS shape (contract section 5). */
    public JsonAssertions isProblem(int status, String code) {
        hasInt("/status", status).hasText("/code", code);
        hasText("/type", "https://oms.example/problems/" + code.toLowerCase().replace('_', '-'));
        hasField("/title").hasField("/timestamp").hasField("/correlationId");
        Assertions.assertThat(node("/errors").isArray()).as("errors is array").isTrue();
        return this;
    }

    /** Contract page envelope: content, page, size, totalElements, totalPages. */
    public JsonAssertions isPage(int size, int page) {
        hasInt("/size", size).hasInt("/page", page).hasField("/totalElements").hasField("/totalPages");
        Assertions.assertThat(node("/content").isArray()).as("content is array").isTrue();
        return this;
    }
}
