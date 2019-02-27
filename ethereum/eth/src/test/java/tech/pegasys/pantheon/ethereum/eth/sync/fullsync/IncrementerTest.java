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
package tech.pegasys.pantheon.ethereum.eth.sync.fullsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.chain.Blockchain;
import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.BlockDataGenerator;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManager;
import tech.pegasys.pantheon.ethereum.eth.manager.EthProtocolManagerTestUtil;
import tech.pegasys.pantheon.ethereum.eth.manager.EthScheduler;
import tech.pegasys.pantheon.ethereum.eth.manager.RespondingEthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.ethtaskutils.BlockchainSetupUtil;
import tech.pegasys.pantheon.ethereum.eth.sync.SynchronizerConfiguration;
import tech.pegasys.pantheon.ethereum.eth.sync.state.SyncState;
import tech.pegasys.pantheon.ethereum.mainnet.ProtocolSchedule;
import tech.pegasys.pantheon.metrics.MetricCategory;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.metrics.Observation;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.metrics.prometheus.PrometheusMetricsSystem;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

public class IncrementerTest {
    private ProtocolSchedule<Void> protocolSchedule;
    private EthProtocolManager ethProtocolManager;
    private EthContext ethContext;
    private ProtocolContext<Void> protocolContext;
    private SyncState syncState;

    private BlockchainSetupUtil<Void> localBlockchainSetup;
    private MutableBlockchain localBlockchain;
    private BlockchainSetupUtil<Void> otherBlockchainSetup;
    private Blockchain otherBlockchain;
    private MetricsSystem metricsSystem = new PrometheusMetricsSystem();

    @Test
    public void parallelDownloadPipelineCounterShouldIncrement() {
        localBlockchainSetup = BlockchainSetupUtil.forTesting();
        localBlockchain = spy(localBlockchainSetup.getBlockchain());
        otherBlockchainSetup = BlockchainSetupUtil.forTesting();
        otherBlockchain = otherBlockchainSetup.getBlockchain();

        protocolSchedule = localBlockchainSetup.getProtocolSchedule();
        protocolContext = localBlockchainSetup.getProtocolContext();
        ethProtocolManager =
                EthProtocolManagerTestUtil.create(
                        localBlockchain,
                        localBlockchainSetup.getWorldArchive(),
                        new EthScheduler(1, 1, 1, new NoOpMetricsSystem()));
        ethContext = ethProtocolManager.ethContext();
        syncState = new SyncState(protocolContext.getBlockchain(), ethContext.getEthPeers());

        otherBlockchainSetup.importFirstBlocks(15);
        final long targetBlock = otherBlockchain.getChainHeadBlockNumber();
        // Sanity check
        assertThat(targetBlock).isGreaterThan(localBlockchain.getChainHeadBlockNumber());

        final RespondingEthPeer peer =
                EthProtocolManagerTestUtil.createPeer(ethProtocolManager, otherBlockchain);
        final RespondingEthPeer.Responder responder =
                RespondingEthPeer.blockchainResponder(otherBlockchain);

        final SynchronizerConfiguration syncConfig =
                SynchronizerConfiguration.builder().downloaderChainSegmentSize(10).build();
        final FullSyncDownloader<?> downloader = downloader(syncConfig);
        downloader.start();

        peer.respondWhileOtherThreadsWork(responder, () -> !syncState.syncTarget().isPresent());
        assertThat(syncState.syncTarget()).isPresent();
        assertThat(syncState.syncTarget().get().peer()).isEqualTo(peer.getEthPeer());

        peer.respondWhileOtherThreadsWork(
                responder, () -> localBlockchain.getChainHeadBlockNumber() < targetBlock);

        assertThat(localBlockchain.getChainHeadBlockNumber()).isEqualTo(targetBlock);

        final List<Observation> metrics = metricsSystem.getMetrics(MetricCategory.SYNCHRONIZER).collect(Collectors.toList());
        for (Observation observation : metrics) {
            if(observation.getMetricName().equals("outboundQueueCounter")){
                assertThat(observation.getValue()).isEqualTo(5.0);
            }
            if(observation.getMetricName().equals("inboundQueueCounter")){
                assertThat(observation.getValue()).isEqualTo(6.0);
            }
        }
    }

    private FullSyncDownloader<?> downloader(final SynchronizerConfiguration syncConfig) {
        return new FullSyncDownloader<>(
                syncConfig, protocolSchedule, protocolContext, ethContext, syncState, metricsSystem);
    }
}
