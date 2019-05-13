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
package tech.pegasys.pantheon.tests.acceptance.jsonrpc;

import static org.assertj.core.api.Assertions.assertThat;

import tech.pegasys.pantheon.tests.acceptance.dsl.AcceptanceTestBase;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.cluster.Cluster;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.cluster.ClusterConfiguration;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.cluster.ClusterConfigurationBuilder;

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

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
    Map<String, Map<String, String>> result = netServices.addPeer(nodeA);
    Map<String, Map<String, String>> expectation = new HashMap<>();
    Map<String, String> constituentMap =
        new HashMap() {
          {
            put("host", "127.0.0.1");
            put("port", "0");
          }
        };
    expectation.put("jsonrpc", constituentMap);
    expectation.put("ws", constituentMap);
    expectation.put("p2p", constituentMap);
    assertThat(expectation.get("jsonrpc").get("host")).isEqualTo(result.get("jsonrpc").get("host"));
    assertThat(expectation.get("jsonrpc").get("port")).isEqualTo(result.get("jsonrpc").get("port"));
    assertThat(expectation.get("ws").get("host")).isEqualTo(result.get("ws").get("host"));
    assertThat(expectation.get("ws").get("port")).isEqualTo(result.get("ws").get("port"));
    assertThat(expectation.get("p2p").get("host")).isEqualTo(result.get("p2p").get("host"));
  }
}
