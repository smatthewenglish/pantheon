package tech.pegasys.pantheon.tests.acceptance.dsl.jsonrpc;

import org.web3j.protocol.core.Response;
import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.RunnableNode;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.JsonRequestFactories;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

public class NetServices {

    public Condition addPeer(final Node node) {

        return new Condition() {
            @Override
            public void verify(Node x) {
                final Boolean result =
                        node.execute(
                                new Transaction<Boolean>() {
                                    @Override
                                    public Boolean execute(JsonRequestFactories requestFactories) {
                                        Response<Boolean> resp = null;
                                        try {
                                            URI enodeUrl = ((RunnableNode) x).enodeUrl();
                                            resp = requestFactories.admin().adminAddPeer(enodeUrl).send();
                                            assertThat(resp).isNotNull();
                                            assertThat(resp.hasError()).isFalse();
                                        } catch (final Exception ignored) {
                                        }
                                        return resp.getResult();
                                    }
                                });
                assertThat(result).isTrue();
            }
        };
    }
}
