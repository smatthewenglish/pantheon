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
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcSuccessResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.results.Quantity;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateArchive;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;
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

    private List<Address> generateAddressList() {
        final byte[] bytes0_address = new byte[]{ (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
        final BytesValue bytesValue0_address = BytesValue.wrap(bytes0_address);
        final Address address0 = Address.wrap(bytesValue0_address);

        final byte[] bytes1_address = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        final BytesValue bytesValue1_address = BytesValue.wrap(bytes1_address);
        final Address address1 = Address.wrap(bytesValue1_address);

        return Arrays.asList(address0, address1);
    }

    private List<UInt256> generateStorageKeyList() {
        final byte a00 = (byte) 0xAA;
        final byte a01 = (byte) 0xAA;
        final byte a02 = (byte) 0xAA;
        final byte a03 = (byte) 0xAA;
        final byte a04 = (byte) 0xAA;
        final byte a05 = (byte) 0xAA;
        final byte a06 = (byte) 0xAA;
        final byte a07 = (byte) 0xAA;
        final byte a08 = (byte) 0xAA;
        final byte a09 = (byte) 0xAA;
        final byte a10 = (byte) 0xAA;
        final byte a11 = (byte) 0xAA;
        final byte a12 = (byte) 0xAA;
        final byte a13 = (byte) 0xAA;
        final byte a14 = (byte) 0xAA;
        final byte a15 = (byte) 0xAA;
        final byte a16 = (byte) 0xAA;
        final byte a17 = (byte) 0xAA;
        final byte a18 = (byte) 0xAA;
        final byte a19 = (byte) 0xAA;
        final byte a20 = (byte) 0xAA;
        final byte a21 = (byte) 0xAA;
        final byte a22 = (byte) 0xAA;
        final byte a23 = (byte) 0xAA;
        final byte a24 = (byte) 0xAA;
        final byte a25 = (byte) 0xAA;
        final byte a26 = (byte) 0xAA;
        final byte a27 = (byte) 0xAA;
        final byte a28 = (byte) 0xAA;
        final byte a29 = (byte) 0xAA;
        final byte a30 = (byte) 0xAA;
        final byte a31 = (byte) 0xAA;
        final byte a32 = (byte) 0xAA;

        final byte[] bytes_storage0 = new byte[]{a00, a01, a02, a03, a04, a05, a06, a07, a08, a09, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, a22, a23, a24, a25, a26, a27, a28, a29, a30, a31, a32};
        final Bytes32 bytes32_storage0 = Bytes32.wrap(bytes_storage0);
        final UInt256 uInt2560_storage0 = UInt256.wrap(bytes32_storage0);

        final byte b00 = (byte) 0xBB;
        final byte b01 = (byte) 0xBB;
        final byte b02 = (byte) 0xBB;
        final byte b03 = (byte) 0xBB;
        final byte b04 = (byte) 0xBB;
        final byte b05 = (byte) 0xBB;
        final byte b06 = (byte) 0xBB;
        final byte b07 = (byte) 0xBB;
        final byte b08 = (byte) 0xBB;
        final byte b09 = (byte) 0xBB;
        final byte b10 = (byte) 0xBB;
        final byte b11 = (byte) 0xBB;
        final byte b12 = (byte) 0xBB;
        final byte b13 = (byte) 0xBB;
        final byte b14 = (byte) 0xBB;
        final byte b15 = (byte) 0xBB;
        final byte b16 = (byte) 0xBB;
        final byte b17 = (byte) 0xBB;
        final byte b18 = (byte) 0xBB;
        final byte b19 = (byte) 0xBB;
        final byte b20 = (byte) 0xBB;
        final byte b21 = (byte) 0xBB;
        final byte b22 = (byte) 0xBB;
        final byte b23 = (byte) 0xBB;
        final byte b24 = (byte) 0xBB;
        final byte b25 = (byte) 0xBB;
        final byte b26 = (byte) 0xBB;
        final byte b27 = (byte) 0xBB;
        final byte b28 = (byte) 0xBB;
        final byte b29 = (byte) 0xBB;
        final byte b30 = (byte) 0xBB;
        final byte b31 = (byte) 0xBB;
        final byte b32 = (byte) 0xBB;

        final byte[] bytes_storage1 = new byte[]{b00, b01, b02, b03, b04, b05, b06, b07, b08, b09, b10, b11, b12, b13, b14, b15, b16, b17, b18, b19, b20, b21, b22, b23, b24, b25, b26, b27, b28, b29, b30, b31, b32};
        final Bytes32 bytes32_storage1 = Bytes32.wrap(bytes_storage1);
        final UInt256 uInt2560_storage1 = UInt256.wrap(bytes32_storage1);

        return Arrays.asList(uInt2560_storage0, uInt2560_storage1);
    }

    @Before
    public void setUp() {

        final WorldStateArchive worldStateArchive = createInMemoryWorldStateArchive();

        final int blockCount = 2;

        /* * */
        final List<Address> addressList = generateAddressList();
        /* * */
        final List<UInt256> storageKeyList = generateStorageKeyList();
        /* * */

        final BlockDataGenerator blockDataGenerator = new BlockDataGenerator();









        // Generate some queries data
        final List<BlockchainQueriesTest.BlockData> blockDataList = new ArrayList<>(blockCount);
        final List<Block> blockList = blockDataGenerator.blockSequence(blockCount, worldStateArchive, addressList, storageKeys);

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
                                    System.out.println("address: " + address);

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
        final String address = "0x73d51abbd89cb8196f0efb6892f94d68fccc2c35";

        //getUint8
        final String payload0 = "0x343a875d";
        /* * */
        final String gasLimit0 = "314159";
        final String gasPrice0 ="1";

        //JsonCallParameter jsonCallParameter = new JsonCallParameter("0x0", "0x0", "0x0", "0x0", "0x0", "");
        JsonCallParameter jsonCallParameter = new JsonCallParameter(address, "0x0", gasLimit0, gasPrice0, "0x0", payload0);

        final JsonRpcRequest jsonRpcRequest = new JsonRpcRequest("2.0", "trace_call", new Object[] {jsonCallParameter, Quantity.create(1L)});

        JsonRpcResponse jsonRpcResponse = traceCall.response(jsonRpcRequest);
        JsonRpcSuccessResponse successResponse = (JsonRpcSuccessResponse) jsonRpcResponse;

        System.out.println(successResponse.getResult());

    }

    @Test
    public void test() {
        final byte[] bytes0 = new byte[]{ (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
        final BytesValue bytesValue0 = BytesValue.wrap(bytes0);
        final Address address0 = Address.wrap(bytesValue0);

        System.out.println("address0: " + address0);

        final byte[] bytes1 = new byte[]{ (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        final BytesValue bytesValue1 = BytesValue.wrap(bytes1);
        final Address address1 = Address.wrap(bytesValue1);

        System.out.println("address1: " + address1);
    }
}
