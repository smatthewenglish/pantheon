package tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods;

import org.junit.Before;
import org.junit.Test;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.Account;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockDataGenerator;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.TransactionReceipt;
import tech.pegasys.pantheon.ethereum.core.WorldState;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.JsonRpcRequest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonCallParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueriesTest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.results.Quantity;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateArchive;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static tech.pegasys.pantheon.ethereum.core.InMemoryStorageProvider.createInMemoryBlockchain;
import static tech.pegasys.pantheon.ethereum.core.InMemoryStorageProvider.createInMemoryWorldStateArchive;

public class TraceCallHyperTest {

    BlockchainQueries blockchainQueries;

    @Before
    public void setUp() {

        // final List<Account> accounts = dataGen.createRandomContractAccountsWithNonEmptyStorage(remoteWorldState, 20);

        final BlockDataGenerator blockDataGenerator = new BlockDataGenerator();

        final WorldStateArchive worldStateArchive = createInMemoryWorldStateArchive();

        final int blockCount = 2;

        // final List<Address> addresses = Arrays.asList(blockDataGenerator.address(), blockDataGenerator.address(), blockDataGenerator.address());
        final List<Address> addresses = Collections.singletonList(blockDataGenerator.address());
        // final List<UInt256> storageKeys = Arrays.asList(blockDataGenerator.storageKey(), blockDataGenerator.storageKey(), blockDataGenerator.storageKey());
        final List<UInt256> storageKeys = Collections.singletonList(blockDataGenerator.storageKey());

        // Generate some queries data
        final List<BlockchainQueriesTest.BlockData> blockDataList = new ArrayList<>(blockCount);
        final List<Block> blockList = blockDataGenerator.blockSequence(blockCount, worldStateArchive, addresses, storageKeys);

        for (int i = 0; i < blockCount; i++) {
            final Block block = blockList.get(i);
            final List<TransactionReceipt> receipts = blockDataGenerator.receipts(block);
            blockDataList.add(new BlockchainQueriesTest.BlockData(block, receipts));
        }

        // Setup blockchain
        final MutableBlockchain blockchain = createInMemoryBlockchain(blockList.get(0));
        blockDataList.subList(1, blockDataList.size()).forEach(b -> blockchain.appendBlock(b.getBlock(), b.getTransactionReceiptList()));

        blockchainQueries = new BlockchainQueries(blockchain, worldStateArchive);

        /* * */

        final BlockchainQueriesTest.BlockchainWithData blockchainWithData = new BlockchainQueriesTest.BlockchainWithData(blockchain, blockDataList, worldStateArchive);

        final Hash latestStateRoot0 = blockchainWithData.getBlockData().get(1).getBlock().getHeader().getStateRoot();
        final WorldState worldState0 = blockchainWithData.getWorldStateArchive().get(latestStateRoot0).get();
        addresses.forEach(
                address ->
                        storageKeys.forEach(
                                storageKey -> {
                                    final Account actualAccount0 = worldState0.get(address);
                                    final UInt256 result = blockchainQueries.storageAt(address, storageKey, 1L).get();

                                    System.out.println("storageKey: " + storageKey);
                                    System.out.println("result: " + result);

                                    assertEquals(actualAccount0.getStorageValue(storageKey), result);
                                }));

    }

    TraceCall traceCall;

    @Test
    public void wireTogetherFireTogether() {
        System.out.println("hello world...");

        final Blockchain blockchain = blockchainQueries.getBlockchain();
        final WorldStateArchive worldStateArchive = blockchainQueries.getWorldStateArchive();
        final ProtocolSchedule protocolSchedule = MainnetProtocolSchedule.create();

        final TransactionSimulator transactionSimulator = new TransactionSimulator(blockchain, worldStateArchive, protocolSchedule);

        final JsonRpcParameter jsonRpcParameter = new JsonRpcParameter();

        traceCall = new TraceCall(blockchainQueries, transactionSimulator, jsonRpcParameter);


        final String storageKey = "108880666909002678955999504419132212304398961865316732214021935797026080667126";


        JsonCallParameter jsonCallParameter = new JsonCallParameter("0x0", "0x0", "0x0", "0x0", "0x0", "");

        final JsonRpcRequest jsonRpcRequest = new JsonRpcRequest("2.0", "trace_call", new Object[] {jsonCallParameter, Quantity.create(1L)});

        JsonRpcResponse jsonRpcResponse = traceCall.response(jsonRpcRequest);

        System.out.println();

    }
}
