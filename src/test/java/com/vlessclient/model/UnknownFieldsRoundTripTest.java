package com.vlessclient.model;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field a newer build wrote survives this one reading and saving the file.
 *
 * <p>Every model ignored what it did not know and saves rewrite whole files,
 * so after a downgrade the first save dropped a newer build's fields, and
 * after the upgrade again they came back as defaults.</p>
 */
class UnknownFieldsRoundTripTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    static Stream<Class<? extends KeepsUnknownFields>> models() {
        return Stream.of(AppSettings.class, ServerConfig.class, TlsConfig.class,
                TransportConfig.class, Subscription.class, RoutingConfig.class,
                RoutingRule.class);
    }

    @ParameterizedTest
    @MethodSource("models")
    void aFieldFromANewerBuildIsWrittenBack(Class<? extends KeepsUnknownFields> model) {
        String newer = "{\"from_a_newer_build\": {\"enabled\": false, \"level\": 3}}";

        Object read = mapper.readValue(newer, model);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(read));

        assertThat(written.path("from_a_newer_build").path("enabled").isBoolean())
                .as("%s kept it", model.getSimpleName()).isTrue();
        assertThat(written.path("from_a_newer_build").path("level").asInt()).isEqualTo(3);
    }

    @ParameterizedTest
    @MethodSource("models")
    void aModelWithNothingUnknownWritesNothingExtra(Class<? extends KeepsUnknownFields> model)
            throws Exception {
        Object fresh = model.getDeclaredConstructor().newInstance();

        JsonNode written = mapper.readTree(mapper.writeValueAsString(fresh));

        assertThat(written.has("from_a_newer_build")).isFalse();
    }
}
