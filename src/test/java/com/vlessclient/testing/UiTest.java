package com.vlessclient.testing;

import com.vlessclient.app.UiServicesExtension;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a TestFX class: it gets the network-free service graph built before
 * it runs and the locator restored after it, and it carries the {@code ui}
 * tag so the slow half of the suite can be left out —
 * {@code mvn test -Dsurefire.excludedGroups=ui,smoke} runs everything else.
 *
 * <p>Headless rendering itself needs nothing per class — surefire sets the
 * Monocle and TestFX properties for the whole suite.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("ui")
@ExtendWith(UiServicesExtension.class)
public @interface UiTest {
}
