package tech.pegasys.pantheon.tests.acceptance.dsl.transaction;

import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

public class NetServicesJsonRpcRequestFactory {

    //public static class ProposalsResponse extends Response<Map<Address, Boolean>> {}
    public static class NetServicesResponse extends Response<Map<String, Map<String, String>>> {

    }

    private final Web3jService web3jService;

    public NetServicesJsonRpcRequestFactory(final Web3jService web3jService) {
        this.web3jService = web3jService;
    }

    public Request<?, NetServicesResponse> netServices(final URI enodeAddress) {

        Request request = new Request<>(
                "net_services",
                Collections.EMPTY_LIST,
                web3jService,
                NetServicesResponse.class);

        return request;
    }
}
