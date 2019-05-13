package tech.pegasys.pantheon.tests.acceptance.jsonrpc;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import tech.pegasys.pantheon.tests.acceptance.dsl.AcceptanceTestBase;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.cluster.Cluster;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.cluster.ClusterConfiguration;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder;

public class NetServicesAcceptanceTest extends AcceptanceTestBase {

    private Cluster noDiscoveryCluster;

    private Node nodeA;
    private Node nodeB;

    @Before
    public void setUp() throws Exception {
        final ClusterConfiguration clusterConfiguration =
                new ClusterConfigurationBuilder().setAwaitPeerDiscovery(false).build();
        noDiscoveryCluster = new Cluster(clusterConfiguration, net);
        nodeA = pantheon.createArchiveNodeWithDiscoveryDisabledAndAdmin("nodeA");
        nodeB = pantheon.createArchiveNodeWithDiscoveryDisabledAndAdmin("nodeB");
        noDiscoveryCluster.start(nodeA, nodeB);
    }

    @Test
    public void adminAddPeerForcesConnection() {

        System.out.println("AAA");

        nodeA.verify(netServices.addPeer(nodeA));

        System.out.println("ZZZ");
    }
}
