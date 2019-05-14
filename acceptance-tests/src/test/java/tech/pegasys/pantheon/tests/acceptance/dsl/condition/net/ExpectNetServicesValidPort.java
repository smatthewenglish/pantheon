package tech.pegasys.pantheon.tests.acceptance.dsl.condition.net;

import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.net.NetServicesTransaction;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.net.NetVersionTransaction;

import static org.assertj.core.api.Assertions.assertThat;

public class ExpectNetServicesValidPort implements Condition {

    private final NetServicesTransaction transaction;

    public ExpectNetServicesValidPort(final NetServicesTransaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public void verify(Node node) {

        final Boolean result = node.execute();
        
        assertThat(result).isTrue();
    }
}
