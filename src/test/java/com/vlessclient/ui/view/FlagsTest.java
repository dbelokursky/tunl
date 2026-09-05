package com.vlessclient.ui.view;

import com.vlessclient.testing.FxToolkitExtension;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The flag badges: which codes draw a flag, what everything else falls back
 * to, and that no code the app can hand over blows up in a list cell.
 */
@ExtendWith(FxToolkitExtension.class)
class FlagsTest {

    /** Every code {@link Flags} draws; anything else gets the lettered chip. */
    private static final List<String> DRAWN = List.of(
            "NL", "RU", "DE", "AT", "BG", "HU", "LT", "EE", "UA", "PL", "ID", "SG",
            "FR", "IT", "IE", "BE", "RO",
            "SE", "FI", "NO", "DK", "IS",
            "JP", "CH", "ES", "PT", "AE",
            "US", "GB", "CN", "TR", "CA", "AU", "VN", "MY");

    private static boolean isChip(Node flag) {
        return flag.lookup(".flag-chip") != null;
    }

    private static String chipText(Node flag) {
        Node text = flag.lookup(".flag-chip-text");
        assertThat(text).as("a lettered chip").isInstanceOf(Text.class);
        return ((Text) text).getText();
    }

    @Test
    void nullBlankAndUnknownCodesFallBackToALetteredChip() {
        assertThat(chipText(Flags.of(null, 18))).isEqualTo("??");
        assertThat(chipText(Flags.of("  ", 18))).isEqualTo("??");
        assertThat(chipText(Flags.of("ZZ", 18))).isEqualTo("ZZ");
        assertThat(chipText(Flags.of("xk", 18)))
                .as("the chip shows the code the way ISO spells it")
                .isEqualTo("XK");
    }

    @Test
    void everyDrawnCodeIsAFlagAtTheRequestedSizeWhateverItsCase() {
        for (String code : DRAWN) {
            for (String spelled : List.of(code, code.toLowerCase(Locale.ROOT), " " + code + " ")) {
                Node flag = Flags.of(spelled, 18);

                assertThat(isChip(flag)).as("'%s' draws a flag, not a chip", spelled).isFalse();
                Bounds bounds = flag.getBoundsInLocal();
                assertThat(bounds.getWidth()).as("'%s' width", spelled).isCloseTo(24, within(0.5));
                assertThat(bounds.getHeight()).as("'%s' height", spelled).isCloseTo(18, within(0.5));
            }
        }
    }

    /**
     * The routing view offers {@code Locale.getISOCountries()} and the GeoIP
     * database can answer with any of them, so every one has to render.
     */
    @Test
    void everyCountryTheAppCanOfferRendersWithoutThrowing() {
        for (String code : Locale.getISOCountries()) {
            Node flag = Flags.of(code, 16);

            if (DRAWN.contains(code)) {
                assertThat(isChip(flag)).as("%s is drawn", code).isFalse();
            } else {
                assertThat(chipText(flag)).as("%s falls back to its letters", code).isEqualTo(code);
            }
        }
    }
}
