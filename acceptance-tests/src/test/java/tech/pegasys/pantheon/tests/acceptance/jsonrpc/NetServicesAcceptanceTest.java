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

    private Node nodeA;

    @Before
    public void setUp() throws Exception {

        nodeA = pantheon.createArchiveNodeWithDiscoveryDisabledAndAdmin("nodeA");
    }

    @Test
    public void adminAddPeerForcesConnection() {

        netServices.addPeer(nodeA);
    }
}
