package tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods;

import org.junit.Before;
import org.junit.Test;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;

public class TraceCallHyperTest {

    TraceCall traceCall;
    TransactionSimulator transactionSimulator;
    BlockchainQueries blockchainQueries;
    ProtocolSchedule<?> protocolSchedule;
    JsonRpcParameter jsonRpcParameter;

    @Before
    public void setUp() {

        blockchainQueries = new BlockchainQueries();

        // protocolSchedule = ...

        transactionSimulator = new TransactionSimulator(
                blockchainQueries.getBlockchain(),
                blockchainQueries.getWorldStateArchive(),
                protocolSchedule);

        traceCall = new TraceCall(blockchainQueries, transactionSimulator, jsonRpcParameter);
    }

    @Test
    public void wireTogetherFireTogether() {
        System.out.println("hello world...");
    }
}
