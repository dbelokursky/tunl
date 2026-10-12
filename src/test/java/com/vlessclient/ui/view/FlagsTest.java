package com.vlessclient.ui.view;

import com.vlessclient.testing.FxToolkitExtension;
import com.vlessclient.app.ThemeCss;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.text.Text;
import org.testfx.util.WaitForAsyncUtils;
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
     * The letters were placed with the default font's measurements, before
     * the stylesheet gave them their size and weight, and sat a few pixels
     * left of and below the middle of the chip. Measured in a scene with the
     * app's stylesheet, as a row draws them.
     */
    @Test
    void theLettersSitInTheMiddleOfTheirChip() throws Exception {
        for (String code : List.of("HK", "KR", "WW")) {
            Bounds[] measured = WaitForAsyncUtils.asyncFx(() -> {
                Node flag = Flags.of(code, 15);
                Scene scene = new Scene(new Group(flag));
                scene.getStylesheets().setAll(ThemeCss.light());
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                Node letters = flag.lookup(".flag-chip-text");
                return new Bounds[] {
                    flag.lookup(".flag-chip").getBoundsInParent(),
                    letters.getParent().localToParent(letters.getBoundsInParent())};
            }).get();
            Bounds chip = measured[0];
            Bounds letters = measured[1];

            // Within a pixel: the layout puts the letters on whole pixels.
            assertThat(letters.getCenterX()).as("%s across", code)
                    .isCloseTo(chip.getCenterX(), within(1.0));
            assertThat(letters.getCenterY()).as("%s down", code)
                    .isCloseTo(chip.getCenterY(), within(1.0));
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
