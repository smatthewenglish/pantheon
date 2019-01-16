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
package tech.pegasys.pantheon.ethereum.p2p.discovery;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.vertx.core.Vertx;
import tech.pegasys.pantheon.crypto.SECP256K1;
import tech.pegasys.pantheon.ethereum.p2p.config.DiscoveryConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.MockPeerDiscoveryAgent;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.MockPeerDiscoveryAgent.IncomingPacket;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.Packet;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PacketType;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PingPacketData;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.ethereum.permissioning.PermissioningConfiguration;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class PeerDiscoveryBootstrappingTest {

  private final PeerDiscoveryTestHelper helper = new PeerDiscoveryTestHelper();

  @Test
  public void bootstrappingPingsSentSingleBootstrapPeer() {
    // Start one test peer and use it as a bootstrap peer.
    final MockPeerDiscoveryAgent testAgent = helper.startDiscoveryAgent();

    // Start an agent.
    final PeerDiscoveryAgent agent = helper.startDiscoveryAgent(testAgent.getAdvertisedPeer());

    final List<IncomingPacket> incomingPackets =
        testAgent
            .getIncomingPackets()
            .stream()
            .filter(p -> p.packet.getType().equals(PacketType.PING))
            .collect(toList());
    assertThat(incomingPackets.size()).isEqualTo(1);
    Packet pingPacket = incomingPackets.get(0).packet;
    assertThat(pingPacket.getNodeId()).isEqualTo(agent.getAdvertisedPeer().getId());

    final PingPacketData pingData = pingPacket.getPacketData(PingPacketData.class).get();
    assertThat(pingData.getExpiration())
        .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
    assertThat(pingData.getFrom()).isEqualTo(agent.getAdvertisedPeer().getEndpoint());
    assertThat(pingData.getTo()).isEqualTo(testAgent.getAdvertisedPeer().getEndpoint());
  }

  @Test
  public void bootstrappingPingsSentMultipleBootstrapPeers() {
    // Use these peers as bootstrap peers.
    final List<MockPeerDiscoveryAgent> bootstrapAgents = helper.startDiscoveryAgents(3);
    final List<DiscoveryPeer> bootstrapPeers =
        bootstrapAgents.stream().map(PeerDiscoveryAgent::getAdvertisedPeer).collect(toList());

    // Start five agents.
    List<MockPeerDiscoveryAgent> agents = helper.startDiscoveryAgents(5, bootstrapPeers);

    // Assert that all test peers received a Find Neighbors packet.
    for (final MockPeerDiscoveryAgent bootstrapAgent : bootstrapAgents) {
      // Five messages per peer (sent by each of the five agents).
      final List<Packet> packets =
          bootstrapAgent.getIncomingPackets().stream().map(p -> p.packet).collect(toList());

      // Assert that the node IDs we received belong to the test agents.
      final List<BytesValue> senderIds =
          packets.stream().map(Packet::getNodeId).distinct().collect(toList());
      final List<BytesValue> agentIds =
          agents
              .stream()
              .map(PeerDiscoveryAgent::getAdvertisedPeer)
              .map(Peer::getId)
              .distinct()
              .collect(toList());

      assertThat(senderIds).containsExactlyInAnyOrderElementsOf(agentIds);

      // Traverse all received pings.
      List<Packet> pingPackets =
          packets.stream().filter(p -> p.getType().equals(PacketType.PING)).collect(toList());
      for (final Packet packet : pingPackets) {
        // Assert that the packet was a Find Neighbors one.
        assertThat(packet.getType()).isEqualTo(PacketType.PING);

        // Assert on the content of the packet data.
        final PingPacketData ping = packet.getPacketData(PingPacketData.class).get();
        assertThat(ping.getExpiration())
            .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
        assertThat(ping.getTo()).isEqualTo(bootstrapAgent.getAdvertisedPeer().getEndpoint());
      }
    }
  }

  @Test
  public void bootstrappingPeersListUpdated() throws InterruptedException {
    // Start an agent.
    final PeerDiscoveryAgent bootstrapAgent = helper.startDiscoveryAgent(emptyList());

    System.out.println("----------> " + bootstrapAgent.getAdvertisedPeer().getId());

    // Start other five agents, pointing to the one above as a bootstrap peer.
    final List<MockPeerDiscoveryAgent> otherAgents = helper.startDiscoveryAgents(5, singletonList(bootstrapAgent.getAdvertisedPeer()));
    final BytesValue[] otherPeersIds = otherAgents.stream().map(PeerDiscoveryAgent::getId).toArray(BytesValue[]::new);

    assertThat(bootstrapAgent.getPeers()).extracting(Peer::getId).containsExactlyInAnyOrder(otherPeersIds);

    assertThat(bootstrapAgent.getPeers()).allMatch(p -> p.getStatus() == DiscoveryPeerStatus.DISPATCHED_PONG_TO);

    // This agent will bootstrap off the bootstrap peer, will add all nodes returned by the latter,
    // and will
    // bond with them, ultimately adding all 7 nodes in the network to its table.
    //final PeerDiscoveryAgent newAgent = helper.startDiscoveryAgent(bootstrapAgent.getAdvertisedPeer());

    final DiscoveryConfiguration config = new DiscoveryConfiguration();
    config.setBootstrapPeers(Collections.singletonList(bootstrapAgent.getAdvertisedPeer()));
    config.setBindPort(0);
    config.setActive(true);

    final PeerDiscoveryAgent newAgent = new VertxPeerDiscoveryAgent(
            Vertx.vertx(),
            SECP256K1.KeyPair.generate(),
            config,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    newAgent.start();

    Thread.sleep(1000);

    for(DiscoveryPeer peer : newAgent.getPeers()) {
      System.out.println("newAgent " + peer.getId());
    }
    System.out.println("-----");

    for(DiscoveryPeer peer : bootstrapAgent.getPeers()) {
      System.out.println("bootstrapAgent " + peer.getId());
    }

    assertThat(newAgent.getPeers()).hasSize(6);
  }


  @Test
  public void deconstructedIncrementalUpdateBootstrapPeersList() {
    final Vertx vertx = Vertx.vertx();

    // Start an agent.
    final DiscoveryConfiguration discoveryConfiguration0 = new DiscoveryConfiguration();
    discoveryConfiguration0.setBootstrapPeers(emptyList());
    discoveryConfiguration0.setBindPort(0);
    final PeerDiscoveryAgent peerDiscoveryAgent0 = new VertxPeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            discoveryConfiguration0,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    peerDiscoveryAgent0.start().join();

    // Start another agent, pointing to the above agent as a bootstrap peer.
    final DiscoveryConfiguration discoveryConfiguration1 = new DiscoveryConfiguration();
    discoveryConfiguration1.setBootstrapPeers(Collections.singletonList(peerDiscoveryAgent0.getAdvertisedPeer()));
    discoveryConfiguration1.setBindPort(0);
    final PeerDiscoveryAgent peerDiscoveryAgent1 = new VertxPeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            discoveryConfiguration1,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    peerDiscoveryAgent1.start().join();

    await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(peerDiscoveryAgent0.getPeers()).hasSize(1));
    await().atMost(5, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(peerDiscoveryAgent1.getPeers()).hasSize(1));

    // This agent will bootstrap off the bootstrap peer, will add all nodes
    // returned by the latter, and will bond with them, ultimately adding all
    // nodes in the network to its table.
    final DiscoveryConfiguration discoveryConfiguration_TEST = new DiscoveryConfiguration();
    discoveryConfiguration_TEST.setBootstrapPeers(Collections.singletonList(peerDiscoveryAgent1.getAdvertisedPeer()));
    discoveryConfiguration_TEST.setBindPort(0);
    final PeerDiscoveryAgent peerDiscoveryAgent_TEST = new VertxPeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            discoveryConfiguration_TEST,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    peerDiscoveryAgent_TEST.start().join();

    await()
            .atMost(10, TimeUnit.SECONDS)
            .untilAsserted(() -> assertThat(peerDiscoveryAgent_TEST.getPeers()).hasSize(2));

    vertx.close();
  }
}
