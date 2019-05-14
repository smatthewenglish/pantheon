package tech.pegasys.pantheon.tests.acceptance.dsl.transaction.net;

import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.NetVersion;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.JsonRequestFactories;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.NetServicesJsonRpcRequestFactory;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;

import java.util.Map;

public class NetServicesTransaction implements Transaction<Map<String, Map<String, String>>> {

    NetServicesTransaction() {}

    @Override
    public Map<String, Map<String, String>> execute(
            final JsonRequestFactories requestFactories) {
        NetServicesJsonRpcRequestFactory.NetServicesResponse netServicesResponse = null;
        try {
            NetServicesJsonRpcRequestFactory netServicesJsonRpcRequestFactory = requestFactories.netServices();
            Request<?, NetServicesJsonRpcRequestFactory.NetServicesResponse> request =
                    netServicesJsonRpcRequestFactory.netServices();
            netServicesResponse = request.send();
        } catch (final Exception ignored) {
        }
        return netServicesResponse.getResult();
    }
}
