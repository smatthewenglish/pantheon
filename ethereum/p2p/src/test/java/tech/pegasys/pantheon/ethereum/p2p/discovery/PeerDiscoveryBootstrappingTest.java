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

import static io.vertx.core.Vertx.vertx;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import tech.pegasys.pantheon.crypto.SECP256K1;
import tech.pegasys.pantheon.ethereum.p2p.config.DiscoveryConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.config.PermissioningConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.Packet;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PacketType;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PingPacketData;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vertx.core.Vertx;
import org.junit.Test;

public class PeerDiscoveryBootstrappingTest extends AbstractPeerDiscoveryTest {

  @Test
  public void bootstrappingPingsSentSingleBootstrapPeer() throws Exception {
    // Start one test peer and use it as a bootstrap peer.
    final DiscoveryTestSocket discoveryTestSocket = startTestSocket();
    final List<DiscoveryPeer> bootstrapPeers = singletonList(discoveryTestSocket.getPeer());

    // Start an agent.
    final PeerDiscoveryAgent agent = startDiscoveryAgent(bootstrapPeers);

    final Packet packet = discoveryTestSocket.getIncomingPackets().poll(2, TimeUnit.SECONDS);

    assertThat(packet.getType()).isEqualTo(PacketType.PING);
    assertThat(packet.getNodeId()).isEqualTo(agent.getAdvertisedPeer().getId());

    final PingPacketData pingData = packet.getPacketData(PingPacketData.class).get();
    assertThat(pingData.getExpiration())
        .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
    assertThat(pingData.getFrom()).isEqualTo(agent.getAdvertisedPeer().getEndpoint());
    assertThat(pingData.getTo()).isEqualTo(discoveryTestSocket.getPeer().getEndpoint());
  }

  @Test
  public void bootstrappingPingsSentMultipleBootstrapPeers() {
    // Start three test peers.
    startTestSockets(3);

    // Use these peers as bootstrap peers.
    final List<DiscoveryPeer> bootstrapPeers =
        discoveryTestSockets.stream().map(DiscoveryTestSocket::getPeer).collect(toList());

    // Start five agents.
    startDiscoveryAgents(5, bootstrapPeers);

    // Assert that all test peers received a Find Neighbors packet.
    for (final DiscoveryTestSocket peer : discoveryTestSockets) {
      // Five messages per peer (sent by each of the five agents).
      final List<Packet> packets = Stream.generate(peer::compulsoryPoll).limit(5).collect(toList());

      // No more messages left.
      assertThat(peer.getIncomingPackets().size()).isEqualTo(0);

      // Assert that the node IDs we received belong to the test agents.
      final List<BytesValue> peerIds = packets.stream().map(Packet::getNodeId).collect(toList());
      final List<BytesValue> nodeIds =
          agents
              .stream()
              .map(PeerDiscoveryAgent::getAdvertisedPeer)
              .map(Peer::getId)
              .collect(toList());

      assertThat(peerIds).containsExactlyInAnyOrderElementsOf(nodeIds);

      // Traverse all received packets.
      for (final Packet packet : packets) {
        // Assert that the packet was a Find Neighbors one.
        assertThat(packet.getType()).isEqualTo(PacketType.PING);

        // Assert on the content of the packet data.
        final PingPacketData ping = packet.getPacketData(PingPacketData.class).get();
        assertThat(ping.getExpiration())
            .isGreaterThanOrEqualTo(System.currentTimeMillis() / 1000 - 10000);
        assertThat(ping.getTo()).isEqualTo(peer.getPeer().getEndpoint());
      }
    }
  }

  @Test
  public void bootstrappingPeersListUpdated() {
    // Start an agent.
    final PeerDiscoveryAgent bootstrapAgent = startDiscoveryAgent(emptyList());

    // Start other five agents, pointing to the one above as a bootstrap peer.
    final List<PeerDiscoveryAgent> otherAgents =
        startDiscoveryAgents(5, singletonList(bootstrapAgent.getAdvertisedPeer()));

    final BytesValue[] otherPeersIds =
        otherAgents
            .stream()
            .map(PeerDiscoveryAgent::getAdvertisedPeer)
            .map(Peer::getId)
            .toArray(BytesValue[]::new);

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(bootstrapAgent.getPeers())
                    .extracting(Peer::getId)
                    .containsExactlyInAnyOrder(otherPeersIds));

    assertThat(bootstrapAgent.getPeers())
        .allMatch(p -> p.getStatus() == PeerDiscoveryStatus.BONDED);

    // This agent will bootstrap off the bootstrap peer, will add all nodes returned by the latter,
    // and will
    // bond with them, ultimately adding all 7 nodes in the network to its table.
    final PeerDiscoveryAgent newAgent =
        startDiscoveryAgent(singletonList(bootstrapAgent.getAdvertisedPeer()));
    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(newAgent.getPeers()).hasSize(6));
  }

  @Test
  public void deconstructedBootstrappingPeersListUpdated() {
    final int BROADCAST_TCP_PORT = 12356;
    final Vertx vertx = vertx();

    // Start an agent.
    final DiscoveryConfiguration bootstrapConfig = new DiscoveryConfiguration();
    bootstrapConfig.setBootstrapPeers(emptyList());
    bootstrapConfig.setBindPort(0);

    final PeerDiscoveryAgent bootstrapAgent =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            bootstrapConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      bootstrapAgent.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }

    // Start other five agents, pointing to the one above as a bootstrap peer.
    final DiscoveryConfiguration agentConfig = new DiscoveryConfiguration();
    agentConfig.setBootstrapPeers(singletonList(bootstrapAgent.getAdvertisedPeer()));
    agentConfig.setBindPort(0);

    final PeerDiscoveryAgent agent0 =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            agentConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      agent0.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }
    BytesValue id0 = agent0.getAdvertisedPeer().getId();

    final PeerDiscoveryAgent agent1 =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            agentConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      agent1.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }
    BytesValue id1 = agent1.getAdvertisedPeer().getId();

    final PeerDiscoveryAgent agent2 =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            agentConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      agent2.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }
    BytesValue id2 = agent2.getAdvertisedPeer().getId();

    final PeerDiscoveryAgent agent3 =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            agentConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      agent3.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }
    BytesValue id3 = agent3.getAdvertisedPeer().getId();

    final PeerDiscoveryAgent agent4 =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            agentConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      agent4.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }
    BytesValue id4 = agent4.getAdvertisedPeer().getId();

    final BytesValue[] otherPeersIds = new BytesValue[] {id0, id1, id2, id3, id4};

    await()
        .atMost(5, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(bootstrapAgent.getPeers())
                    .extracting(Peer::getId)
                    .containsExactlyInAnyOrder(otherPeersIds));

    assertThat(bootstrapAgent.getPeers())
        .allMatch(p -> p.getStatus() == PeerDiscoveryStatus.BONDED);

    // This agent will bootstrap off the bootstrap peer, will add all nodes returned by the latter,
    // and will
    // bond with them, ultimately adding all 7 nodes in the network to its table.
    final DiscoveryConfiguration testConfig = new DiscoveryConfiguration();
    testConfig.setBootstrapPeers(singletonList(bootstrapAgent.getAdvertisedPeer()));
    testConfig.setBindPort(0);

    final PeerDiscoveryAgent testAgent =
        new PeerDiscoveryAgent(
            vertx,
            SECP256K1.KeyPair.generate(),
            testConfig,
            () -> true,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()));
    try {
      testAgent.start(BROADCAST_TCP_PORT).get(5, TimeUnit.SECONDS);
    } catch (final Exception ex) {
      throw new AssertionError("Could not initialize discovery agent", ex);
    }

    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(() -> assertThat(testAgent.getPeers()).hasSize(6));
    vertx.close();
  }
}
