package tech.pegasys.pantheon.ethereum.eth.sync.fullsync;

import org.junit.Test;
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
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.metrics.noop.NoOpMetricsSystem;
import tech.pegasys.pantheon.metrics.prometheus.PrometheusMetricsSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

public class IncrementerTest {
    private ProtocolSchedule<Void> protocolSchedule;
    private EthProtocolManager ethProtocolManager;
    private EthContext ethContext;
    private ProtocolContext<Void> protocolContext;
    private SyncState syncState;

    private BlockDataGenerator gen;
    private BlockchainSetupUtil<Void> localBlockchainSetup;
    private MutableBlockchain localBlockchain;
    private BlockchainSetupUtil<Void> otherBlockchainSetup;
    private Blockchain otherBlockchain;
    //private MetricsSystem metricsSystem = new PrometheusMetricsSystem();

    @Test
    public void test() {
        gen = new BlockDataGenerator();
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
        final RespondingEthPeer.Responder responder = RespondingEthPeer.blockchainResponder(otherBlockchain);

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

        //System.out.println("----> " + metricsSystem.getParallelDownloadPipelineCounter().getCount());
    }
//    private FullSyncDownloader<?> downloader(final SynchronizerConfiguration syncConfig) {
//        return new FullSyncDownloader<>(syncConfig, protocolSchedule, protocolContext, ethContext, syncState, metricsSystem);
//    }
}