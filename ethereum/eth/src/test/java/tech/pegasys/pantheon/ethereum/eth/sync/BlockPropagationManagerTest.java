/*
 * Copyright 2018 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.sync;

import com.google.common.collect.Range;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import tech.pegasys.pantheon.config.GenesisConfigFile;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.chain.BlockchainStorage;
import tech.pegasys.pantheon.ethereum.chain.DefaultMutableBlockchain;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockBody;
import tech.pegasys.pantheon.ethereum.core.BlockDataGenerator;
import tech.pegasys.pantheon.ethereum.core.BlockDataGenerator.BlockOptions;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.BlockHeaderBuilder;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.InMemoryStorageProvider;
import tech.pegasys.pantheon.ethereum.core.LogsBloomFilter;
import tech.pegasys.pantheon.ethereum.core.PrivacyParameters;
import tech.pegasys.pantheon.ethereum.core.TransactionReceipt;
import tech.pegasys.pantheon.ethereum.eth.EthProtocol;
import tech.pegasys.pantheon.ethereum.eth.manager.DeterministicEthScheduler;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthMessages;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeers;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManager;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManagerTestUtil;
import tech.pegasys.pantheon.ethereum.eth.manager.EthScheduler;
import tech.pegasys.pantheon.ethereum.eth.manager.MockPeerConnection;
import tech.pegasys.pantheon.ethereum.eth.manager.RespondingEthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.RespondingEthPeer.Responder;
import tech.pegasys.pantheon.ethereum.eth.manager.ethtaskutils.BlockchainSetupUtil;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockHashesMessage;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockHashesMessage.NewBlockHash;
import tech.pegasys.pantheon.ethereum.eth.messages.NewBlockMessage;
import tech.pegasys.pantheon.ethereum.eth.sync.state.PendingBlocks;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetBlockHashFunction;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolScheduleBuilder;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.ethereum.p2p.wire.DefaultMessage;
import tech.pegasys.pantheon.ethereum.storage.keyvalue.KeyValueStorageWorldStateStorage;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateArchive;
import tech.pegasys.pantheon.metrics.LabelledMetric;
import tech.pegasys.pantheon.metrics.OperationTimer;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.services.kvstore.InMemoryKeyValueStorage;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static sun.security.krb5.Confounder.bytes;

public class BlockPropagationManagerTest {

  private static Blockchain fullBlockchain;

  private BlockchainSetupUtil<Void> blockchainUtil;
  private ProtocolSchedule<Void> protocolSchedule;
  private ProtocolContext<Void> protocolContext;
  private MutableBlockchain blockchain;
  private EthProtocolManager ethProtocolManager;
  private BlockPropagationManager<Void> blockPropagationManager;
  private SynchronizerConfiguration syncConfig;
  private final PendingBlocks pendingBlocks = new PendingBlocks();
  private SyncState syncState;
  private final LabelledMetric<OperationTimer> ethTasksTimer =
      NoOpMetricsSystem.NO_OP_LABELLED_TIMER;

  @BeforeClass
  public static void setupSuite() {
    fullBlockchain = BlockchainSetupUtil.forTesting().importAllBlocks();
  }

  @Before
  public void setup() {
    blockchainUtil = BlockchainSetupUtil.forTesting();
    blockchain = spy(blockchainUtil.getBlockchain());
    protocolSchedule = blockchainUtil.getProtocolSchedule();
    final ProtocolContext<Void> tempProtocolContext = blockchainUtil.getProtocolContext();
    protocolContext =
        new ProtocolContext<>(
            blockchain,
            tempProtocolContext.getWorldStateArchive(),
            tempProtocolContext.getConsensusState());
    ethProtocolManager =
        EthProtocolManagerTestUtil.create(blockchain, blockchainUtil.getWorldArchive());
    syncConfig =
        SynchronizerConfiguration.builder()
            .blockPropagationRange(-3, 5)
            .build()
            .validated(blockchain);
    syncState = new SyncState(blockchain, ethProtocolManager.ethContext().getEthPeers());
    blockPropagationManager =
        new BlockPropagationManager<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethProtocolManager.ethContext(),
            syncState,
            pendingBlocks,
            ethTasksTimer);
  }

  @Test
  public void blockPropagationManager_functionalityAssessment() {

    BlockchainStorage kvStore00 = new InMemoryStorageProvider().createBlockchainStorage(MainnetProtocolSchedule.create());
    BlockHeader genesisHeader00 =
            BlockHeaderBuilder.create()
                    .parentHash(Hash.ZERO)
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(0L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody genesisBody00 = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block genesisBlock00 = new Block(genesisHeader00, genesisBody00);
    DefaultMutableBlockchain blockchain00 = new DefaultMutableBlockchain(genesisBlock00, kvStore00, new NoOpMetricsSystem());

    BlockHeader header00A =
            BlockHeaderBuilder.create()
                    .parentHash(genesisBlock00.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(1L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body00A = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block00A = new Block(header00A, body00A);
    List<TransactionReceipt> receipts00A = Collections.emptyList();
    blockchain00.appendBlock(block00A, receipts00A);

    BlockHeader header00B =
            BlockHeaderBuilder.create()
                    .parentHash(block00A.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(2L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body00B = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block00B = new Block(header00B, body00B);
    List<TransactionReceipt> receipts00B = Collections.emptyList();
    blockchain00.appendBlock(block00B, receipts00B);

    BlockHeader header00C =
            BlockHeaderBuilder.create()
                    .parentHash(block00B.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(3L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body00C = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block00C = new Block(header00C, body00C);
    List<TransactionReceipt> receipts00C = Collections.emptyList();
    blockchain00.appendBlock(block00C, receipts00C);

    BlockHeader header00D =
            BlockHeaderBuilder.create()
                    .parentHash(block00C.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(4L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body00D = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block00D = new Block(header00D, body00D);
    List<TransactionReceipt> receipts00D = Collections.emptyList();
    blockchain00.appendBlock(block00D, receipts00D);

    assertThat(blockchain00.getChainHeadBlockNumber()).isEqualTo(4L);

    /* * */

    BlockchainStorage kvStore01 = new InMemoryStorageProvider().createBlockchainStorage(MainnetProtocolSchedule.create());
    BlockHeader genesisHeader01 =
            BlockHeaderBuilder.create()
                    .parentHash(Hash.ZERO)
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(0L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody genesisBody01 = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block genesisBlock01 = new Block(genesisHeader01, genesisBody01);
    DefaultMutableBlockchain blockchain01 = new DefaultMutableBlockchain(genesisBlock01, kvStore01, new NoOpMetricsSystem());

    BlockHeader header01A =
            BlockHeaderBuilder.create()
                    .parentHash(genesisBlock01.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(1L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body01A = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block01A = new Block(header01A, body01A);
    List<TransactionReceipt> receipts01A = Collections.emptyList();
    blockchain01.appendBlock(block01A, receipts01A);

    BlockHeader header01B =
            BlockHeaderBuilder.create()
                    .parentHash(block01A.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(2L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body01B = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block01B = new Block(header01B, body01B);
    List<TransactionReceipt> receipts01B = Collections.emptyList();
    blockchain01.appendBlock(block01B, receipts01B);

    BlockHeader header01C =
            BlockHeaderBuilder.create()
                    .parentHash(block01B.getHash())
                    .ommersHash(Hash.ZERO)
                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                    .stateRoot(Hash.ZERO)
                    .transactionsRoot(Hash.ZERO)
                    .receiptsRoot(Hash.ZERO)
                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                    .difficulty(UInt256.ZERO)
                    .number(3L)
                    .gasLimit(1L)
                    .gasUsed(1L)
                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                    .mixHash(Hash.ZERO)
                    .nonce(0L)
                    .blockHashFunction(MainnetBlockHashFunction::createHash)
                    .buildBlockHeader();
    BlockBody body01C = new BlockBody(Collections.emptyList(), Collections.emptyList());
    Block block01C = new Block(header01C, body01C);
    List<TransactionReceipt> receipts01C = Collections.emptyList();
    blockchain01.appendBlock(block01C, receipts01C);

    assertThat(blockchain01.getChainHeadBlockNumber()).isEqualTo(3L);

    WorldStateArchive worldStateArchive00 = new WorldStateArchive(new KeyValueStorageWorldStateStorage(new InMemoryKeyValueStorage()));

    ProtocolContext<Void> protocolContext00 = new ProtocolContext(blockchain00, worldStateArchive00, null);

    int fastSyncPivotDistance = 500;
    float fastSyncFullValidationRate = .1f;
    SyncMode syncMode = SyncMode.FULL;
    Range<Long> blockPropagationRange = Range.closed(-10L, 30L);
    long downloaderChangeTargetThresholdByHeight = 20L;
    UInt256 downloaderChangeTargetThresholdByTd = UInt256.of(1_000_000_000L);
    int downloaderHeaderRequestSize = 10;
    int downloaderCheckpointTimeoutsPermitted = 5;
    int downloaderChainSegmentTimeoutsPermitted = 5;
    int downloaderChainSegmentSize = 20;
    long trailingPeerBlocksBehindThreshold = 3L;
    int maxTrailingPeers = Integer.MAX_VALUE;
    int downloaderParallelism = 2;
    int transactionsParallelism = 2;
    int DEFAULT_FAST_SYNC_MINIMUM_PEERS = 5;
    Duration DEFAULT_FAST_SYNC_MAXIMUM_PEER_WAIT_TIME = Duration.ofMinutes(3);

    SynchronizerConfiguration synchronizerConfiguration00 = new SynchronizerConfiguration(
            syncMode,
            fastSyncPivotDistance,
            fastSyncFullValidationRate,
            DEFAULT_FAST_SYNC_MINIMUM_PEERS,
            DEFAULT_FAST_SYNC_MAXIMUM_PEER_WAIT_TIME,
            blockPropagationRange,
            Optional.empty(),
            downloaderChangeTargetThresholdByHeight,
            downloaderChangeTargetThresholdByTd,
            downloaderHeaderRequestSize,
            downloaderCheckpointTimeoutsPermitted,
            downloaderChainSegmentTimeoutsPermitted,
            downloaderChainSegmentSize,
            trailingPeerBlocksBehindThreshold,
            maxTrailingPeers,
            downloaderParallelism,
            transactionsParallelism);

    int DEFAULT_CHAIN_ID00 = 1;
    ProtocolSchedule<Void> protocolSchedule00 = new ProtocolScheduleBuilder<>(GenesisConfigFile.mainnet().getConfigOptions(), DEFAULT_CHAIN_ID00, Function.identity(), PrivacyParameters.noPrivacy())
            .createProtocolSchedule();

    final int networkId00 = 1;
    final EthScheduler ethScheduler00 = new DeterministicEthScheduler(DeterministicEthScheduler.TimeoutPolicy.NEVER);

    EthProtocolManager ethProtocolManager00 = new EthProtocolManager(
            blockchain00,
            worldStateArchive00,
            networkId00,
            false,
            EthProtocolManager.DEFAULT_REQUEST_LIMIT,
            ethScheduler00);

    SyncState syncState00 = new SyncState(blockchain00, ethProtocolManager00.ethContext().getEthPeers());

    PendingBlocks pendingBlocks00 = new PendingBlocks();

    LabelledMetric<OperationTimer> ethTasksTimer00 = NoOpMetricsSystem.NO_OP_LABELLED_TIMER;

    BlockPropagationManager blockPropagationManager00 = new BlockPropagationManager<>(
            synchronizerConfiguration00,
            protocolSchedule00,
            protocolContext00,
            ethProtocolManager00.ethContext(),
            syncState00,
            pendingBlocks00,
            ethTasksTimer00);

    // ...
    blockPropagationManager00.start();

    /* * */

    // Setup peer and messages
    final RespondingEthPeer respondingEthPeer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage newBlockHashesMessage = NewBlockHashesMessage.create(singletonList(new NewBlockHash(block00D.getHash(), block00D.getHeader().getNumber())));
    final Responder responder = RespondingEthPeer.blockchainResponder(blockchain01);

    // Broadcast first message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, respondingEthPeer, newBlockHashesMessage);
    respondingEthPeer.respondWhile(responder, respondingEthPeer::hasOutstandingRequests);

    assertThat(blockchain00.contains(block00D.getHash())).isTrue();
  }

  @Test
  public void importsAnnouncedBlocks_aheadOfChainInOrder() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);
    final Block nextNextBlock = blockchainUtil.getBlock(3);

    // Sanity check
    assertThat(blockchain.contains(nextBlock.getHash())).isFalse();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage nextAnnouncement = NewBlockHashesMessage.create(singletonList(new NewBlockHash(nextBlock.getHash(), nextBlock.getHeader().getNumber())));
    final NewBlockHashesMessage nextNextAnnouncement =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(nextNextBlock.getHash(), nextNextBlock.getHeader().getNumber())));
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);

    // Broadcast first message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast second message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextNextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(nextBlock.getHash())).isTrue();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isTrue();
  }

  @Test
  public void importsAnnouncedBlocks_aheadOfChainOutOfOrder() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);
    final Block nextNextBlock = blockchainUtil.getBlock(3);

    // Sanity check
    assertThat(blockchain.contains(nextBlock.getHash())).isFalse();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage nextAnnouncement =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(nextBlock.getHash(), nextBlock.getHeader().getNumber())));
    final NewBlockHashesMessage nextNextAnnouncement =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(nextNextBlock.getHash(), nextNextBlock.getHeader().getNumber())));
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);

    // Broadcast second message first
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextNextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast first message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(nextBlock.getHash())).isTrue();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isTrue();
  }

  @Test
  public void importsAnnouncedNewBlocks_aheadOfChainInOrder() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);
    final Block nextNextBlock = blockchainUtil.getBlock(3);

    // Sanity check
    assertThat(blockchain.contains(nextBlock.getHash())).isFalse();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockMessage nextAnnouncement =
        NewBlockMessage.create(
            nextBlock, fullBlockchain.getTotalDifficultyByHash(nextBlock.getHash()).get());
    final NewBlockMessage nextNextAnnouncement =
        NewBlockMessage.create(
            nextNextBlock, fullBlockchain.getTotalDifficultyByHash(nextNextBlock.getHash()).get());
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);

    // Broadcast first message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast second message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextNextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(nextBlock.getHash())).isTrue();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isTrue();
  }

  @Test
  public void importsAnnouncedNewBlocks_aheadOfChainOutOfOrder() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);
    final Block nextNextBlock = blockchainUtil.getBlock(3);

    // Sanity check
    assertThat(blockchain.contains(nextBlock.getHash())).isFalse();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockMessage nextAnnouncement =
        NewBlockMessage.create(
            nextBlock, fullBlockchain.getTotalDifficultyByHash(nextBlock.getHash()).get());
    final NewBlockMessage nextNextAnnouncement =
        NewBlockMessage.create(
            nextNextBlock, fullBlockchain.getTotalDifficultyByHash(nextNextBlock.getHash()).get());
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);

    // Broadcast second message first
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextNextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast first message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextAnnouncement);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(nextBlock.getHash())).isTrue();
    assertThat(blockchain.contains(nextNextBlock.getHash())).isTrue();
  }

  @Test
  public void importsMixedOutOfOrderMessages() {
    blockchainUtil.importFirstBlocks(2);
    final Block block1 = blockchainUtil.getBlock(2);
    final Block block2 = blockchainUtil.getBlock(3);
    final Block block3 = blockchainUtil.getBlock(4);
    final Block block4 = blockchainUtil.getBlock(5);

    // Sanity check
    assertThat(blockchain.contains(block1.getHash())).isFalse();
    assertThat(blockchain.contains(block2.getHash())).isFalse();
    assertThat(blockchain.contains(block3.getHash())).isFalse();
    assertThat(blockchain.contains(block4.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage block1Msg =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(block1.getHash(), block1.getHeader().getNumber())));
    final NewBlockMessage block2Msg =
        NewBlockMessage.create(
            block2, fullBlockchain.getTotalDifficultyByHash(block2.getHash()).get());
    final NewBlockHashesMessage block3Msg =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(block3.getHash(), block3.getHeader().getNumber())));
    final NewBlockMessage block4Msg =
        NewBlockMessage.create(
            block4, fullBlockchain.getTotalDifficultyByHash(block4.getHash()).get());
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);

    // Broadcast older blocks
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, block3Msg);
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, block4Msg);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, block2Msg);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast first block
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, block1Msg);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(block1.getHash())).isTrue();
    assertThat(blockchain.contains(block2.getHash())).isTrue();
    assertThat(blockchain.contains(block3.getHash())).isTrue();
    assertThat(blockchain.contains(block4.getHash())).isTrue();
  }

  @Test
  public void handlesDuplicateAnnouncements() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);

    // Sanity check
    assertThat(blockchain.contains(nextBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage newBlockHash =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(nextBlock.getHash(), nextBlock.getHeader().getNumber())));
    final NewBlockMessage newBlock =
        NewBlockMessage.create(
            nextBlock, fullBlockchain.getTotalDifficultyByHash(nextBlock.getHash()).get());
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);

    // Broadcast first message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlock);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast duplicate
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlockHash);
    peer.respondWhile(responder, peer::hasOutstandingRequests);
    // Broadcast duplicate
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlock);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(nextBlock.getHash())).isTrue();
    verify(blockchain, times(1)).appendBlock(any(), any());
  }

  @Test
  public void handlesPendingDuplicateAnnouncements() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);

    // Sanity check
    assertThat(blockchain.contains(nextBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage newBlockHash =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(nextBlock.getHash(), nextBlock.getHeader().getNumber())));
    final NewBlockMessage newBlock =
        NewBlockMessage.create(
            nextBlock, fullBlockchain.getTotalDifficultyByHash(nextBlock.getHash()).get());

    // Broadcast messages
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlock);
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlockHash);
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlock);
    // Respond
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(nextBlock.getHash())).isTrue();
    verify(blockchain, times(1)).appendBlock(any(), any());
  }

  @Test
  public void ignoresFutureNewBlockHashAnnouncement() {
    blockchainUtil.importFirstBlocks(2);
    final Block futureBlock = blockchainUtil.getBlock(11);

    // Sanity check
    assertThat(blockchain.contains(futureBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage futureAnnouncement =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(futureBlock.getHash(), futureBlock.getHeader().getNumber())));

    // Broadcast
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, futureAnnouncement);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(futureBlock.getHash())).isFalse();
  }

  @Test
  public void ignoresFutureNewBlockAnnouncement() {
    blockchainUtil.importFirstBlocks(2);
    final Block futureBlock = blockchainUtil.getBlock(11);

    // Sanity check
    assertThat(blockchain.contains(futureBlock.getHash())).isFalse();

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockMessage futureAnnouncement =
        NewBlockMessage.create(
            futureBlock, fullBlockchain.getTotalDifficultyByHash(futureBlock.getHash()).get());

    // Broadcast
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, futureAnnouncement);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(blockchain.contains(futureBlock.getHash())).isFalse();
  }

  @Test
  public void ignoresOldNewBlockHashAnnouncement() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    blockchainUtil.importFirstBlocks(10);
    final Block blockOne = blockchainUtil.getBlock(1);
    final Block oldBlock = gen.nextBlock(blockOne);

    // Sanity check
    assertThat(blockchain.contains(oldBlock.getHash())).isFalse();

    final BlockPropagationManager<Void> propManager = spy(blockPropagationManager);
    propManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockHashesMessage oldAnnouncement =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(oldBlock.getHash(), oldBlock.getHeader().getNumber())));

    // Broadcast
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, oldAnnouncement);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    verify(propManager, times(0)).importOrSavePendingBlock(any());
    assertThat(blockchain.contains(oldBlock.getHash())).isFalse();
  }

  @Test
  public void ignoresOldNewBlockAnnouncement() {
    final BlockDataGenerator gen = new BlockDataGenerator();
    blockchainUtil.importFirstBlocks(10);
    final Block blockOne = blockchainUtil.getBlock(1);
    final Block oldBlock = gen.nextBlock(blockOne);

    // Sanity check
    assertThat(blockchain.contains(oldBlock.getHash())).isFalse();

    final BlockPropagationManager<Void> propManager = spy(blockPropagationManager);
    propManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockMessage oldAnnouncement = NewBlockMessage.create(oldBlock, UInt256.ZERO);

    // Broadcast
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, oldAnnouncement);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    verify(propManager, times(0)).importOrSavePendingBlock(any());
    assertThat(blockchain.contains(oldBlock.getHash())).isFalse();
  }

  @Test
  public void purgesOldBlocks() {
    final int oldBlocksToImport = 3;
    syncConfig =
        SynchronizerConfiguration.builder()
            .blockPropagationRange(-oldBlocksToImport, 5)
            .build()
            .validated(blockchain);
    final BlockPropagationManager<Void> blockPropagationManager =
        new BlockPropagationManager<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethProtocolManager.ethContext(),
            syncState,
            pendingBlocks,
            ethTasksTimer);

    final BlockDataGenerator gen = new BlockDataGenerator();
    // Import some blocks
    blockchainUtil.importFirstBlocks(5);
    // Set up test block next to head, that should eventually be purged
    final Block blockToPurge =
        gen.block(BlockOptions.create().setBlockNumber(blockchain.getChainHeadBlockNumber()));

    blockPropagationManager.start();
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final NewBlockMessage blockAnnouncementMsg = NewBlockMessage.create(blockToPurge, UInt256.ZERO);

    // Broadcast
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, blockAnnouncementMsg);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    // Check that we pushed our block into the pending collection
    assertThat(blockchain.contains(blockToPurge.getHash())).isFalse();
    assertThat(pendingBlocks.contains(blockToPurge.getHash())).isTrue();

    // Import blocks until we bury the target block far enough to be cleaned up
    for (int i = 0; i < oldBlocksToImport; i++) {
      blockchainUtil.importBlockAtIndex((int) blockchain.getChainHeadBlockNumber() + 1);

      assertThat(blockchain.contains(blockToPurge.getHash())).isFalse();
      assertThat(pendingBlocks.contains(blockToPurge.getHash())).isTrue();
    }

    // Import again to trigger cleanup
    blockchainUtil.importBlockAtIndex((int) blockchain.getChainHeadBlockNumber() + 1);
    assertThat(blockchain.contains(blockToPurge.getHash())).isFalse();
    assertThat(pendingBlocks.contains(blockToPurge.getHash())).isFalse();
  }

  @Test
  public void updatesChainHeadWhenNewBlockMessageReceived() {
    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);

    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final UInt256 totalDifficulty =
        fullBlockchain.getTotalDifficultyByHash(nextBlock.getHash()).get();
    final NewBlockMessage nextAnnouncement = NewBlockMessage.create(nextBlock, totalDifficulty);

    // Broadcast message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextAnnouncement);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(peer.getEthPeer().chainState().getBestBlock().getHash())
        .isEqualTo(nextBlock.getHash());
    assertThat(peer.getEthPeer().chainState().getEstimatedHeight())
        .isEqualTo(nextBlock.getHeader().getNumber());
    assertThat(peer.getEthPeer().chainState().getBestBlock().getTotalDifficulty())
        .isEqualTo(totalDifficulty);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldNotImportBlocksThatAreAlreadyBeingImported() {
    final EthScheduler ethScheduler = mock(EthScheduler.class);
    when(ethScheduler.scheduleSyncWorkerTask(any(Supplier.class)))
        .thenReturn(new CompletableFuture<>());
    final EthContext ethContext =
        new EthContext("eth", new EthPeers("eth"), new EthMessages(), ethScheduler);
    final BlockPropagationManager<Void> blockPropagationManager =
        new BlockPropagationManager<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethContext,
            syncState,
            pendingBlocks,
            ethTasksTimer);

    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);

    blockPropagationManager.importOrSavePendingBlock(nextBlock);
    blockPropagationManager.importOrSavePendingBlock(nextBlock);

    verify(ethScheduler, times(1)).scheduleSyncWorkerTask(any(Supplier.class));
  }
}
