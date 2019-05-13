package tech.pegasys.pantheon.tests.acceptance.dsl.jsonrpc;

import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.NetVersion;
import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.RunnableNode;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.JsonRequestFactories;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.NetServicesJsonRpcRequestFactory;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class NetServices {

    public Condition addPeer(final Node node) {

        System.out.println("999");

        return new Condition() {
            @Override
            public void verify(Node x) {

                System.out.println("888");

                final Map<String, Map<String, String>> result =
                        node.execute(
                                new Transaction<Map<String, Map<String, String>>>() {
                                    @Override
                                    public Map<String, Map<String, String>> execute(JsonRequestFactories requestFactories) {

                                        System.out.println("777");

                                        NetServicesJsonRpcRequestFactory.NetServicesResponse c = null;
                                        try {

                                            //System.out.println("netVersion: " + requestFactories.net().netVersion().send().getNetVersion());

                                            //System.out.println("netVersion: " + requestFactories.net().netListening().send().isListening());

                                            URI enodeUrl = ((RunnableNode) x).enodeUrl();

                                            System.out.println("xxx");

                                            NetServicesJsonRpcRequestFactory s = requestFactories.netServices();

                                            System.out.println("yyy");

                                            Request<?, NetServicesJsonRpcRequestFactory.NetServicesResponse> t = s.netServices(enodeUrl);

                                            System.out.println("zzz");

                                            c = t.send();

                                            //System.out.println("--> " + c.getResult());

                                            System.out.println("aaa");

                                            //resp = requestFactories.admin().adminAddPeer(enodeUrl).send();
                                            //assertThat(resp).isNotNull();
                                            //assertThat(resp.hasError()).isFalse();
                                        } catch (final Exception ignored) {
                                        }
                                        return c.getResult();
                                    }
                                });
                //assertThat(result).isTrue();
                System.out.println("--> " + result);
            }
        };
    }
}
