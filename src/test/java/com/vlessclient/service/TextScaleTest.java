package com.vlessclient.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The OS's text size as a factor, and base.css's font rules scaled by it. */
class TextScaleTest {

    @Test
    void theUsualSizeOfEachSystemIsAFactorOfOne() {
        assertThat(TextScale.factor(12, true)).isEqualTo(1.0);
        assertThat(TextScale.factor(13, false)).isEqualTo(1.0);
    }

    @Test
    void aLargerSystemTextSizeIsItsRatioToTheUsual() {
        assertThat(TextScale.factor(18, true)).isEqualTo(1.5);
        assertThat(TextScale.factor(19.5, false)).isEqualTo(1.5);
    }

    @Test
    void theTextNeverShrinksAndStopsGrowingAtThreeTimes() {
        assertThat(TextScale.factor(11, true)).as("smaller than usual").isEqualTo(1.0);
        assertThat(TextScale.factor(12.1, true)).as("rounding, not a choice").isEqualTo(1.0);
        assertThat(TextScale.factor(60, true)).isEqualTo(3.0);
    }

    @Test
    void theFontSizesAndTheBoxesTheTextSitsInAreRepeatedScaled() {
        String css = """
                /* a comment { -fx-font-size: 99px; } */
                .view-title {
                    -fx-font-size: 22px;
                    -fx-font-weight: bold;
                }
                .card { -fx-padding: 12px; }
                .update-banner .banner-title, .hint-label { -fx-font-size: 12.5px; }
                .primary-button { -fx-min-height: 34; -fx-pref-height: 34px; }
                .sidebar { -fx-pref-width: 212; }
                .separator { -fx-min-height: 1; -fx-pref-height: -1; }
                """;

        assertThat(TextScale.scaledRules(css, 1.5)).isEqualTo("""
                .view-title { -fx-font-size: 33.0px; }
                .update-banner .banner-title, .hint-label { -fx-font-size: 18.8px; }
                .primary-button { -fx-min-height: 51.0px; -fx-pref-height: 51.0px; }
                .sidebar { -fx-pref-width: 318.0px; }
                """);
    }

    @Test
    void aStylesheetBecomesADataUrlOfItsText() {
        String url = TextScale.dataUrl(".a { -fx-font-size: 20.0px; }");

        assertThat(url).startsWith("data:text/css;base64,");
        assertThat(new String(Base64.getDecoder().decode(
                url.substring("data:text/css;base64,".length())), StandardCharsets.UTF_8))
                .isEqualTo(".a { -fx-font-size: 20.0px; }");
    }
}
