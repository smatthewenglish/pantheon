/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.pantheon.ethereum.eth.manager.task;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManager;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManagerTestUtil;
import tech.pegasys.pantheon.ethereum.eth.manager.RespondingEthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.ethtaskutils.BlockchainSetupUtil;
import tech.pegasys.pantheon.metrics.LabelledMetric;
import tech.pegasys.pantheon.metrics.OperationTimer;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class DeconstructedGetNodeDataFromPeerTaskTest {

  @Test
  public void getNodeDataFromPeerTaskTest() {
    final BlockchainSetupUtil<Void> blockchainSetupUtil = BlockchainSetupUtil.forTesting();
    blockchainSetupUtil.importAllBlocks();
    final Blockchain blockchain = blockchainSetupUtil.getBlockchain();
    final ProtocolContext<Void> protocolContext = blockchainSetupUtil.getProtocolContext();
    final LabelledMetric<OperationTimer> ethTasksTimer = NoOpMetricsSystem.NO_OP_LABELLED_TIMER;
    assertThat(blockchainSetupUtil.getMaxBlockNumber() >= 20L).isTrue();

    final AtomicBoolean peersDoTimeout = new AtomicBoolean(false);
    final AtomicInteger peerCountToTimeout = new AtomicInteger(0);
    final EthProtocolManager ethProtocolManager =
        EthProtocolManagerTestUtil.create(
            blockchain,
            protocolContext.getWorldStateArchive(),
            () -> peerCountToTimeout.getAndDecrement() > 0 || peersDoTimeout.get());
    final EthContext ethContext = ethProtocolManager.ethContext();

    // Setup a responsive peer
    final RespondingEthPeer.Responder responder =
        RespondingEthPeer.blockchainResponder(blockchain, protocolContext.getWorldStateArchive());
    final RespondingEthPeer respondingPeer =
        EthProtocolManagerTestUtil.createPeer(ethProtocolManager, 1000);

    // Setup data to be requested and expected response
    final List<BytesValue> requestedData = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      final BlockHeader blockHeader = blockchain.getBlockHeader(10 + i).get();
      requestedData.add(
          protocolContext.getWorldStateArchive().getNodeData(blockHeader.getStateRoot()).get());
    }

    // Execute task and wait for response
    final AtomicReference<AbstractPeerTask.PeerTaskResult<Map<Hash, BytesValue>>> actualResult =
        new AtomicReference<>();
    final AtomicBoolean done = new AtomicBoolean(false);

    final List<Hash> hashes = requestedData.stream().map(Hash::hash).collect(toList());
    final EthTask<AbstractPeerTask.PeerTaskResult<Map<Hash, BytesValue>>> task =
        GetNodeDataFromPeerTask.forHashes(ethContext, hashes, ethTasksTimer);

    final CompletableFuture<AbstractPeerTask.PeerTaskResult<Map<Hash, BytesValue>>> future =
        task.run();
    respondingPeer.respondWhile(responder, () -> !future.isDone());
    future.whenComplete(
        (result, error) -> {
          actualResult.set(result);
          done.compareAndSet(false, true);
        });

    assertThat(done).isTrue();

    final List<BytesValue> resultData = new ArrayList<>(actualResult.get().getResult().values());

    assertThat(resultData).containsExactlyInAnyOrderElementsOf(requestedData);
    assertThat(actualResult.get().getPeer()).isEqualTo(respondingPeer.getEthPeer());
  }
}
