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

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static sun.security.krb5.Confounder.bytes;

import com.google.common.collect.Range;
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
import tech.pegasys.pantheon.ethereum.p2p.api.MessageData;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.ethereum.p2p.wire.PeerInfo;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage;
import tech.pegasys.pantheon.ethereum.storage.keyvalue.KeyValueStorageWorldStateStorage;
import tech.pegasys.pantheon.ethereum.worldstate.WorldStateArchive;
import tech.pegasys.pantheon.metrics.LabelledMetric;
import tech.pegasys.pantheon.metrics.OperationTimer;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.services.kvstore.InMemoryKeyValueStorage;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.net.SocketAddress;
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

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class BlockPropagationManagerTest {

  private static Blockchain fullBlockchain;

  private BlockchainSetupUtil<Void> blockchainUtil;
  private ProtocolSchedule<Void> protocolSchedule;
  private ProtocolContext<Void> protocolContext;
  private MutableBlockchain blockchain;
  private BlockBroadcaster blockBroadcaster;
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
    syncConfig = SynchronizerConfiguration.builder().blockPropagationRange(-3, 5).build();
    syncState = new SyncState(blockchain, ethProtocolManager.ethContext().getEthPeers());
    blockBroadcaster = mock(BlockBroadcaster.class);
    blockPropagationManager =
        new BlockPropagationManager<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethProtocolManager.ethContext(),
            syncState,
            pendingBlocks,
            ethTasksTimer,
            blockBroadcaster);
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
    final NewBlockHashesMessage nextAnnouncement =
        NewBlockHashesMessage.create(
            singletonList(
                new NewBlockHash(nextBlock.getHash(), nextBlock.getHeader().getNumber())));
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
        SynchronizerConfiguration.builder().blockPropagationRange(-oldBlocksToImport, 5).build();
    final BlockPropagationManager<Void> blockPropagationManager =
        new BlockPropagationManager<>(
            syncConfig,
            protocolSchedule,
            protocolContext,
            ethProtocolManager.ethContext(),
            syncState,
            pendingBlocks,
            ethTasksTimer,
            blockBroadcaster);

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
    final UInt256 parentTotalDifficulty =
        fullBlockchain.getTotalDifficultyByHash(nextBlock.getHeader().getParentHash()).get();
    final UInt256 totalDifficulty =
        fullBlockchain.getTotalDifficultyByHash(nextBlock.getHash()).get();
    final NewBlockMessage nextAnnouncement = NewBlockMessage.create(nextBlock, totalDifficulty);

    // Broadcast message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, nextAnnouncement);
    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    assertThat(peer.getEthPeer().chainState().getBestBlock().getHash())
        .isEqualTo(nextBlock.getHeader().getParentHash());
    assertThat(peer.getEthPeer().chainState().getEstimatedHeight())
        .isEqualTo(nextBlock.getHeader().getNumber() - 1);
    assertThat(peer.getEthPeer().chainState().getBestBlock().getTotalDifficulty())
        .isEqualTo(parentTotalDifficulty);
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
            ethTasksTimer,
            blockBroadcaster);

    blockchainUtil.importFirstBlocks(2);
    final Block nextBlock = blockchainUtil.getBlock(2);

    blockPropagationManager.importOrSavePendingBlock(nextBlock);
    blockPropagationManager.importOrSavePendingBlock(nextBlock);

    verify(ethScheduler, times(1)).scheduleSyncWorkerTask(any(Supplier.class));
  }

  @Test
  public void verifyBroadcastBlockInvocation() {
    final BlockPropagationManager<Void> blockPropagationManager =
        spy(
            new BlockPropagationManager<>(
                syncConfig,
                protocolSchedule,
                protocolContext,
                ethProtocolManager.ethContext(),
                syncState,
                pendingBlocks,
                ethTasksTimer,
                blockBroadcaster));

    blockchainUtil.importFirstBlocks(2);
    final Block block = blockchainUtil.getBlock(2);
    blockPropagationManager.start();

    // Setup peer and messages
    final RespondingEthPeer peer = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);

    final UInt256 totalDifficulty = fullBlockchain.getTotalDifficultyByHash(block.getHash()).get();
    final NewBlockMessage newBlockMessage = NewBlockMessage.create(block, totalDifficulty);

    // Broadcast message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer, newBlockMessage);

    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer.respondWhile(responder, peer::hasOutstandingRequests);

    verify(blockPropagationManager, times(1)).broadcastBlock(block);
  }

  @Test
  public void x() {
    final BlockPropagationManager<Void> blockPropagationManager =
            spy(
                    new BlockPropagationManager<>(
                            syncConfig,
                            protocolSchedule,
                            protocolContext,
                            ethProtocolManager.ethContext(),
                            syncState,
                            pendingBlocks,
                            ethTasksTimer,
                            blockBroadcaster));

    blockchainUtil.importFirstBlocks(2);
    final Block block = blockchainUtil.getBlock(2);
    blockPropagationManager.start();


    // Setup peer and messages
    final RespondingEthPeer peer0 = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final RespondingEthPeer peer1 = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);
    final RespondingEthPeer peer2 = EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 0);

    System.out.println("0 ---> blockchainUtil.getBlockchain().getChainHeadBlockNumber(): " + blockchainUtil.getBlockchain().getChainHeadBlockNumber());
    System.out.println("0 ---> peer.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber(): " + peer0.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber());
    System.out.println("0 ---> peer.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber(): " + peer1.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber());
    System.out.println("0 ---> peer.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber(): " + peer2.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber());

    final UInt256 totalDifficulty = fullBlockchain.getTotalDifficultyByHash(block.getHash()).get();
    final NewBlockMessage newBlockMessage = NewBlockMessage.create(block, totalDifficulty);

    // Broadcast message
    EthProtocolManagerTestUtil.broadcastMessage(ethProtocolManager, peer0, newBlockMessage);

    final Responder responder = RespondingEthPeer.blockchainResponder(fullBlockchain);
    peer0.respondWhile(responder, peer0::hasOutstandingRequests);

    System.out.println("1 ---> blockchainUtil.getBlockchain().getChainHeadBlockNumber(): " + blockchainUtil.getBlockchain().getChainHeadBlockNumber());
    System.out.println("1 ---> peer.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber(): " + peer0.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber());
    System.out.println("1 ---> peer.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber(): " + peer1.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber());
    System.out.println("1 ---> peer.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber(): " + peer2.getEthProtocolManager().getBlockchain().getChainHeadBlockNumber());


    //verify(blockPropagationManager, times(1)).broadcastBlock(block);
  }

  @SuppressWarnings("unchecked")
  @Test
  public void blockPropagationManager_functionalityAssessment() {

    final EthPeers ethPeers = new EthPeers("eth");

    final BytesValue id0 = createId(0);
    final PeerConnection peerConnection0 = new StubbedPeerConnection(id0);
    // Right... but a peer connection is only *part* of a _single_ eth peer-- which would have the info about
    // it's own blockchain... 

    final BytesValue id1 = createId(1);
    final PeerConnection peerConnection1 = new StubbedPeerConnection(id1);

    final BytesValue id2 = createId(2);
    final PeerConnection peerConnection2 = new StubbedPeerConnection(id2);

    ethPeers.registerConnection(peerConnection0);
    ethPeers.registerConnection(peerConnection1);
    ethPeers.registerConnection(peerConnection2);



    System.out.println("ethPeers.peerCount(): " + ethPeers.peerCount());












//
//
//
//
//    final BlockHeader genesisHeader =
//            BlockHeaderBuilder.create()
//                    .parentHash(Hash.ZERO)
//                    .ommersHash(Hash.ZERO)
//                    .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
//                    .stateRoot(Hash.ZERO)
//                    .transactionsRoot(Hash.ZERO)
//                    .receiptsRoot(Hash.ZERO)
//                    .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
//                    .difficulty(UInt256.ZERO)
//                    .number(0L)
//                    .gasLimit(1L)
//                    .gasUsed(1L)
//                    .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
//                    .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
//                    .mixHash(Hash.ZERO)
//                    .nonce(0L)
//                    .blockHashFunction(MainnetBlockHashFunction::createHash)
//                    .buildBlockHeader();
//    final BlockBody genesisBody = new BlockBody(Collections.emptyList(), Collections.emptyList());
//    final Block genesisBlock = new Block(genesisHeader, genesisBody);
//
//    final BlockchainStorage blockchainStorage00 = new InMemoryStorageProvider().createBlockchainStorage(MainnetProtocolSchedule.create());
//    final MutableBlockchain blockchain00 = generateBlockchain(blockchainStorage00, genesisBlock, 4);
//    assertThat(blockchain00.getChainHeadBlockNumber()).isEqualTo(4L);
//
//    final BlockchainStorage blockchainStorage01 = new InMemoryStorageProvider().createBlockchainStorage(MainnetProtocolSchedule.create());
//    final MutableBlockchain blockchain01 = generateBlockchain(blockchainStorage01, genesisBlock, 3);
//    assertThat(blockchain01.getChainHeadBlockNumber()).isEqualTo(3L);
//
//    final SynchronizerConfiguration synchronizerConfiguration = SynchronizerConfiguration.builder().build();
//
//    final WorldStateArchive worldStateArchive = new WorldStateArchive(new KeyValueStorageWorldStateStorage(new InMemoryKeyValueStorage()));
//
//    final EthProtocolManager ethProtocolManager00 = new EthProtocolManager(
//            blockchain00,
//            worldStateArchive,
//            1,
//            false,
//            200,
//            new DeterministicEthScheduler(DeterministicEthScheduler.TimeoutPolicy.NEVER));
//
//    final BlockPropagationManager blockPropagationManager00 = new BlockPropagationManager<>(
//            synchronizerConfiguration,
//            new ProtocolScheduleBuilder<>(GenesisConfigFile.mainnet().getConfigOptions(), 1, Function.identity(), PrivacyParameters.noPrivacy()).createProtocolSchedule(),
//            new ProtocolContext(blockchain00, worldStateArchive, null),
//            ethProtocolManager00.ethContext(),
//            new SyncState(blockchain00, ethProtocolManager00.ethContext().getEthPeers()),
//            new PendingBlocks(),
//            NoOpMetricsSystem.NO_OP_LABELLED_TIMER,
//            blockBroadcaster);
//
//    blockPropagationManager00.start();

  }

  private MutableBlockchain generateBlockchain(BlockchainStorage blockchainStorage, Block genesisBlock, int head) {
    DefaultMutableBlockchain blockchain = new DefaultMutableBlockchain(genesisBlock, blockchainStorage, new NoOpMetricsSystem());
    for (int i = 1; i <= head; i++) {
      BlockHeader header =
              BlockHeaderBuilder.create()
                      .parentHash(blockchain.getBlockHashByNumber(Long.valueOf(i - 1)).get())
                      .ommersHash(Hash.ZERO)
                      .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
                      .stateRoot(Hash.ZERO)
                      .transactionsRoot(Hash.ZERO)
                      .receiptsRoot(Hash.ZERO)
                      .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
                      .difficulty(UInt256.ZERO)
                      .number(Long.valueOf(i))
                      .gasLimit(1L)
                      .gasUsed(1L)
                      .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
                      .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
                      .mixHash(Hash.ZERO)
                      .nonce(0L)
                      .blockHashFunction(MainnetBlockHashFunction::createHash)
                      .buildBlockHeader();
      BlockBody body = new BlockBody(Collections.emptyList(), Collections.emptyList());
      Block block = new Block(header, body);
      List<TransactionReceipt> receipts = Collections.emptyList();
      blockchain.appendBlock(block, receipts);
    }
    return blockchain;
  }

  private static class StubbedPeerConnection implements PeerConnection {
    private final BytesValue nodeId;

    public StubbedPeerConnection(final BytesValue nodeId) {
      this.nodeId = nodeId;
    }

    @Override
    public void send(final Capability capability, final MessageData message)
            throws PeerNotConnected {}

    @Override
    public Set<Capability> getAgreedCapabilities() {
      return null;
    }

    @Override
    public PeerInfo getPeer() {
      return new PeerInfo(0, "IbftIntTestPeer", emptyList(), 0, nodeId);
    }

    @Override
    public void terminateConnection(final DisconnectMessage.DisconnectReason reason, final boolean peerInitiated) {}

    @Override
    public void disconnect(final DisconnectMessage.DisconnectReason reason) {}

    @Override
    public SocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public SocketAddress getRemoteAddress() {
      return null;
    }
  }

  private static BytesValue createId(final int id) {
    return BytesValue.fromHexString(String.format("%0128x", id));
  }
}
