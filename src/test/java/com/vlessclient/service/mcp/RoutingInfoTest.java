package com.vlessclient.service.mcp;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingInfoTest {

    @Test
    void snapshotCopiesAndFreezesSourceLists() {
        List<String> bypassList = new ArrayList<>(List.of("example.com"));
        List<RoutingInfo.RuleInfo> rules = new ArrayList<>(List.of(
                new RoutingInfo.RuleInfo("rule-1", "domain", "example.com", "direct")));

        RoutingInfo snapshot = new RoutingInfo("route_all", bypassList, rules);
        bypassList.add("changed.example");
        rules.clear();

        assertThat(snapshot.bypassList()).containsExactly("example.com");
        assertThat(snapshot.rules()).extracting(RoutingInfo.RuleInfo::id)
                .containsExactly("rule-1");
        assertThatThrownBy(() -> snapshot.bypassList().add("rejected.example"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(snapshot.rules()::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
