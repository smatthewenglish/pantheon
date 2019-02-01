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
package tech.pegasys.pantheon.ethereum.p2p.discovery.internal;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryStatus;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryTestHelper;
import tech.pegasys.pantheon.ethereum.p2p.peers.Endpoint;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.ethereum.permissioning.PermissioningConfiguration;
import tech.pegasys.pantheon.util.Subscribers;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PeerDiscoveryTableRefreshTest {

  @Test
  public void tableRefreshSingleNode() {
    // final List<SECP256K1.KeyPair> keypairs = PeerDiscoveryTestHelper.generateKeyPairs(2);

    final String LOOPBACK_IP_ADDR = "127.0.0.1";
    final AtomicInteger nextAvailablePort = new AtomicInteger(1);
    final int port = nextAvailablePort.incrementAndGet();

    final List<KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(2);

    final KeyPair keyPair0 = keyPairs.get(0);
    final BytesValue id0 = keyPair0.getPublicKey().getEncodedBytes();
    final DiscoveryPeer peer0 = new DiscoveryPeer(id0, LOOPBACK_IP_ADDR, port, port);

    final KeyPair keyPair1 = keyPairs.get(1);
    final BytesValue id1 = keyPair1.getPublicKey().getEncodedBytes();
    final DiscoveryPeer peer1 = new DiscoveryPeer(id1, LOOPBACK_IP_ADDR, port, port);

    /* * */

    final List<DiscoveryPeer> peers = new ArrayList<>();

    peers.add(peer0);
    peers.add(peer1);

    // Create and start the PeerDiscoveryController
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    final MockTimerUtil timer = new MockTimerUtil();
    final PeerDiscoveryController controller =
        spy(
            new PeerDiscoveryController(
                keyPair0,
                peer0,
                new PeerTable(peer0.getId()),
                emptyList(),
                outboundMessageHandler,
                timer,
                0,
                () -> true,
                new PeerBlacklist(),
                new NodeWhitelistController(PermissioningConfiguration.createDefault()),
                new Subscribers<>()));
    controller.start();

    // Send a PING, so as to add a Peer in the controller.
    final PingPacketData ping =
        PingPacketData.create(peers.get(1).getEndpoint(), peers.get(0).getEndpoint());
    final Packet packet = Packet.create(PacketType.PING, ping, keyPairs.get(1));
    controller.onMessage(packet, peers.get(1));

    // Wait until the controller has added the newly found peer.
    assertThat(controller.getPeers()).hasSize(1);

    // As the controller performs refreshes, it'll send FIND_NEIGHBORS packets with random target
    // IDs every time.
    // We capture the packets so that we can later assert on them.
    // Within 1000ms, there should be ~10 packets. But let's be less ambitious and expect at least
    // 5.
    final ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
    for (int i = 0; i < 6; i++) {
      timer.runPeriodicHandlers();
      controller.getRecursivePeerRefreshState().cancelCurrentRound();
      // TODO: We iterate over '6', 'cause the first time around the peer will
      // TODO: be ignored, since by virtue of having PING'd us, it'll be BONDED
      controller.getPeers().forEach(p -> p.setStatus(PeerDiscoveryStatus.KNOWN));
    }
    verify(outboundMessageHandler, atLeast(5)).send(any(), captor.capture());
    final List<Packet> capturedFindNeighborsPackets =
        captor
            .getAllValues()
            .stream()
            .filter(p -> p.getType().equals(PacketType.PING))
            .collect(Collectors.toList());
    assertThat(capturedFindNeighborsPackets.size()).isEqualTo(5);

    // Collect targets from find neighbors packets
    final List<Endpoint> targets = new ArrayList<>();
    for (final Packet captured : capturedFindNeighborsPackets) {
      final Optional<PingPacketData> maybeData = captured.getPacketData(PingPacketData.class);
      assertThat(maybeData).isPresent();
      final PingPacketData pingPacketData = maybeData.get();
      targets.add(pingPacketData.getTo());
    }

    assertThat(targets.size()).isEqualTo(5);

    // All targets are the same.
    assertThat(new HashSet<>(targets).size()).isEqualTo(1);
  }
}
