package tech.pegasys.pantheon.tests.acceptance.dsl.jsonrpc;

import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.NetVersion;
import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.RunnableNode;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.JsonRequestFactories;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

public class NetServices {

    public Condition addPeer(final Node node) {

        System.out.println("999");

        return new Condition() {
            @Override
            public void verify(Node x) {

                System.out.println("888");

                final Boolean result =
                        node.execute(
                                new Transaction<Boolean>() {
                                    @Override
                                    public Boolean execute(JsonRequestFactories requestFactories) {

                                        System.out.println("777");

                                        Response<Boolean> resp = null;
                                        try {

                                            System.out.println("netVersion: " + requestFactories.net().netVersion().send().getNetVersion());

                                            System.out.println("netVersion: " + requestFactories.net().netListening().send().isListening());

                                            URI enodeUrl = ((RunnableNode) x).enodeUrl();

                                            System.out.println("-->: " + requestFactories.netServices().netServices(enodeUrl).send().getJsonrpc());


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
