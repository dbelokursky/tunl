package com.vlessclient.testing;

import com.vlessclient.app.UiServicesExtension;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a TestFX class: it gets the network-free service graph built before
 * it runs and the locator restored after it.
 *
 * <p>Headless rendering itself needs nothing per class — surefire sets the
 * Monocle and TestFX properties for the whole suite.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(UiServicesExtension.class)
public @interface UiTest {
}
