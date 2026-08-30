package com.vlessclient.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.vlessclient.app.ExternalNetworkGuardExtension;
import java.net.ProxySelector;
import org.junit.jupiter.api.Test;

class ExternalNetworkGuardActivationTest {

    @Test
    void automaticallyInstallsTheGuardForUiTests() {
        assertThat(ProxySelector.getDefault())
                .isInstanceOf(
                        ExternalNetworkGuardExtension.NoNetworkProxySelector.class);
    }
}
