package tech.pegasys.pantheon.tests.acceptance.dsl.transaction;

import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;

import java.net.URI;
import java.util.Collections;

public class NetServicesJsonRpcRequestFactory {

    public static class NetServicesResponse extends Response<Boolean> {}

    private final Web3jService web3jService;

    public NetServicesJsonRpcRequestFactory(final Web3jService web3jService) {
        this.web3jService = web3jService;
    }

    public Request<?, NetServicesResponse> netServices(final URI enodeAddress) {
        return new Request<>(
                "net_services",
                Collections.singletonList(enodeAddress.toASCIIString()),
                web3jService,
                NetServicesResponse.class);
    }
}
