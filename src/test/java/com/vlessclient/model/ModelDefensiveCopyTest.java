package com.vlessclient.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDefensiveCopyTest {

    @Test
    void collectionSettersDetachFromCallerOwnedContainers() {
        List<HealthCheckTarget> targets = new ArrayList<>();
        AppSettings settings = new AppSettings();
        settings.setHealthCheckTargets(targets);

        List<String> bypassList = new ArrayList<>();
        List<RoutingRule> rules = new ArrayList<>();
        RoutingConfig routing = new RoutingConfig();
        routing.setBypassList(bypassList);
        routing.setRules(rules);

        List<String> serverIds = new ArrayList<>();
        Subscription subscription = new Subscription();
        subscription.setServerIds(serverIds);

        Map<String, String> headers = new HashMap<>();
        TransportConfig transport = new TransportConfig();
        transport.setHeaders(headers);

        targets.add(new HealthCheckTarget("Example", "https://example.com"));
        bypassList.add("example.com");
        rules.add(new RoutingRule());
        serverIds.add("server-1");
        headers.put("Host", "example.com");

        assertThat(settings.getHealthCheckTargets()).isEmpty();
        assertThat(routing.getBypassList()).isEmpty();
        assertThat(routing.getRules()).isEmpty();
        assertThat(subscription.getServerIds()).isEmpty();
        assertThat(transport.getHeaders()).isEmpty();
    }

    @Test
    void serverSettersCopyNestedMutableModels() {
        TransportConfig transport = new TransportConfig();
        transport.setPath("/original");
        transport.getHeaders().put("Host", "original.example");
        TlsConfig tls = new TlsConfig();
        tls.setServerName("original.example");

        ServerConfig server = new ServerConfig();
        server.setTransport(transport);
        server.setTls(tls);

        transport.setPath("/changed");
        transport.getHeaders().put("Host", "changed.example");
        tls.setServerName("changed.example");

        assertThat(server.getTransport().getPath()).isEqualTo("/original");
        assertThat(server.getTransport().getHeaders())
                .containsEntry("Host", "original.example");
        assertThat(server.getTls().getServerName()).isEqualTo("original.example");
    }
}
