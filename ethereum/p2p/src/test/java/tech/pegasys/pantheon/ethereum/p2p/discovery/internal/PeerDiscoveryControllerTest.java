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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tech.pegasys.pantheon.crypto.SECP256K1;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryStatus;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryTestHelper;
import tech.pegasys.pantheon.ethereum.p2p.peers.Endpoint;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.ethereum.permissioning.PermissioningConfiguration;
import tech.pegasys.pantheon.util.Subscribers;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;
import tech.pegasys.pantheon.util.bytes.MutableBytesValue;
import tech.pegasys.pantheon.util.uint.UInt256;
import tech.pegasys.pantheon.util.uint.UInt256Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PeerDiscoveryControllerTest {

  private static final byte MOST_SIGNFICANT_BIT_MASK = -128;
  private static final RetryDelayFunction LONG_DELAY_FUNCTION = (prev) -> 999999999L;
  private static final RetryDelayFunction SHORT_DELAY_FUNCTION = (prev) -> Math.max(100, prev * 2);
  private static final PeerRequirement PEER_REQUIREMENT = () -> true;
  private static final long TABLE_REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);
  private PeerDiscoveryController controller;
  private DiscoveryPeer localPeer;
  private PeerTable peerTable;
  private KeyPair localKeyPair;
  private final AtomicInteger counter = new AtomicInteger(1);
  private final PeerDiscoveryTestHelper helper = new PeerDiscoveryTestHelper();

  @Before
  public void initializeMocks() {
    final List<KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    localKeyPair = keyPairs.get(0);
    localPeer = helper.createDiscoveryPeer(localKeyPair);
    peerTable = new PeerTable(localPeer.getId());
  }

  @After
  public void stopTable() {
    if (controller != null) {
      controller.stop().join();
    }
  }

  @Test
  public void bootstrapPeersRetriesSent() {
    // Create peers.
    final int peerCount = 3;
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(peerCount);
    final List<DiscoveryPeer> peers = helper.createDiscoveryPeers(keyPairs);

    final MockTimerUtil timer = spy(new MockTimerUtil());
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers)
            .timerUtil(timer)
            .outboundMessageHandler(outboundMessageHandler)
            .build();
    controller.setRetryDelayFunction(SHORT_DELAY_FUNCTION);

    // Mock the creation of the PING packet, so that we can control the hash,
    // which gets validated when receiving the PONG.
    final PingPacketData mockPing =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet mockPacket = Packet.create(PacketType.PING, mockPing, keyPairs.get(0));
    doReturn(mockPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.start();

    final int timeouts = 4;
    for (int i = 0; i < timeouts; i++) {
      timer.runTimerHandlers();
    }
    final int expectedTimerEvents = 1 + (timeouts + 1) * peerCount;
    verify(timer, times(expectedTimerEvents)).setTimer(anyLong(), any());

    // Within this time period, 4 timers should be placed with these timeouts.
    final long[] expectedTimeouts = {100, 200, 400, 800};
    for (final long timeout : expectedTimeouts) {
      verify(timer, times(peerCount)).setTimer(eq(timeout), any());
    }

    // Check that 5 PING packets were sent for each peer (the initial + 4 attempts following
    // timeouts).
    peers.forEach(
        p ->
            verify(outboundMessageHandler, times(timeouts + 1))
                .send(eq(p), matchPacketOfType(PacketType.PING)));

    controller
        .getPeers()
        .forEach(p -> assertThat(p.getStatus()).isEqualTo(PeerDiscoveryStatus.BONDING));
  }

  @Test
  public void bootstrapPeersRetriesStoppedUponResponse() {
    // Create peers.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(3);
    final List<DiscoveryPeer> peers = helper.createDiscoveryPeers(keyPairs);

    final MockTimerUtil timer = new MockTimerUtil();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers)
            .timerUtil(timer)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    // Mock the creation of the PING packet, so that we can control the hash,
    // which gets validated when receiving the PONG.
    final PingPacketData mockPing =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet mockPacket = Packet.create(PacketType.PING, mockPing, keyPairs.get(0));
    doReturn(mockPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.start();

    // Invoke timers several times so that ping to peers should be resent
    for (int i = 0; i < 3; i++) {
      timer.runTimerHandlers();
    }

    // Assert PING packet was sent for peer[0] 4 times.
    for (final DiscoveryPeer peer : peers) {
      verify(outboundMessageHandler, times(4)).send(eq(peer), matchPacketOfType(PacketType.PING));
    }

    // Simulate a PONG message from peer 0.
    final PongPacketData packetData =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet packet = Packet.create(PacketType.PONG, packetData, keyPairs.get(0));
    controller.onMessage(packet, peers.get(0));

    // Invoke timers again
    for (int i = 0; i < 4; i++) {
      timer.runTimerHandlers();
    }

    // Ensure we receive no more PING packets for peer[0].
    // Assert PING packet was sent for peer[0] 4 times.
    for (final DiscoveryPeer peer : peers) {
      final int expectedCount = peer.equals(peers.get(0)) ? 4 : 8;
      verify(outboundMessageHandler, times(expectedCount))
          .send(eq(peer), matchPacketOfType(PacketType.PING));
    }
  }

  @Test
  public void bootstrapPeersPongReceived_HashMatched() {
    // Create peers.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(3);
    final List<DiscoveryPeer> peers = helper.createDiscoveryPeers(keyPairs);

    final MockTimerUtil timer = new MockTimerUtil();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers)
            .timerUtil(timer)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    // Mock the creation of the PING packet, so that we can control the hash, which gets validated
    // when receiving the PONG.
    final PingPacketData mockPing =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet mockPacket = Packet.create(PacketType.PING, mockPing, keyPairs.get(0));
    doReturn(mockPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.start();

    assertThat(
            controller
                .getPeers()
                .stream()
                .filter(p -> p.getStatus() == PeerDiscoveryStatus.BONDING))
        .hasSize(3);

    // Simulate a PONG message from peer 0.
    final PongPacketData packetData0 =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet packet0 = Packet.create(PacketType.PONG, packetData0, keyPairs.get(0));
    controller.onMessage(packet0, peers.get(0));

    final PongPacketData packetData1 =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet packet1 = Packet.create(PacketType.PONG, packetData1, keyPairs.get(1));
    controller.onMessage(packet1, peers.get(1));

    final PongPacketData packetData2 =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet packet2 = Packet.create(PacketType.PONG, packetData2, keyPairs.get(2));
    controller.onMessage(packet2, peers.get(2));

    // Ensure that the peer controller is now sending FIND_NEIGHBORS messages for this peer.
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.FIND_NEIGHBORS));
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(1)), matchPacketOfType(PacketType.FIND_NEIGHBORS));
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(2)), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    // Invoke timeouts and check that we resent our neighbors request
    timer.runTimerHandlers();
    verify(outboundMessageHandler, times(2))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.FIND_NEIGHBORS));
    verify(outboundMessageHandler, times(2))
        .send(eq(peers.get(1)), matchPacketOfType(PacketType.FIND_NEIGHBORS));
    verify(outboundMessageHandler, times(2))
        .send(eq(peers.get(2)), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    assertThat(
            controller
                .getPeers()
                .stream()
                .filter(p -> p.getStatus() == PeerDiscoveryStatus.BONDING))
        .hasSize(0);
    assertThat(
            controller.getPeers().stream().filter(p -> p.getStatus() == PeerDiscoveryStatus.BONDED))
        .hasSize(3);
  }

  @Test
  public void bootstrapPeersPongReceived_HashUnmatched() {
    // Create peers.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(3);
    final List<DiscoveryPeer> peers = helper.createDiscoveryPeers(keyPairs);

    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder().peers(peers).outboundMessageHandler(outboundMessageHandler).build();
    controller.setRetryDelayFunction(LONG_DELAY_FUNCTION);

    // Mock the creation of the PING packet, so that we can control the hash, which gets validated
    // when
    // processing the PONG.
    final PingPacketData mockPing =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet mockPacket = Packet.create(PacketType.PING, mockPing, keyPairs.get(0));
    doReturn(mockPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.start();

    assertThat(
            controller
                .getPeers()
                .stream()
                .filter(p -> p.getStatus() == PeerDiscoveryStatus.BONDING))
        .hasSize(3);

    // Send a PONG packet from peer 1, with an incorrect hash.
    final PongPacketData packetData =
        PongPacketData.create(localPeer.getEndpoint(), BytesValue.fromHexString("1212"));
    final Packet packet = Packet.create(PacketType.PONG, packetData, keyPairs.get(1));
    controller.onMessage(packet, peers.get(1));

    // No FIND_NEIGHBORS packet was sent for peer 1.
    verify(outboundMessageHandler, never())
        .send(eq(peers.get(1)), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    assertThat(
            controller
                .getPeers()
                .stream()
                .filter(p -> p.getStatus() == PeerDiscoveryStatus.BONDING))
        .hasSize(3);
  }

  @Test
  public void findNeighborsSentAfterBondingFinished() {
    // Create three peers, out of which the first two are bootstrap peers.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final List<DiscoveryPeer> peers = helper.createDiscoveryPeers(keyPairs);

    // Initialize the peer controller, setting a high controller refresh interval and a high timeout
    // threshold,
    // to avoid retries getting in the way of this test.
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers.get(0))
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    // Mock the creation of the PING packet, so that we can control the hash, which gets validated
    // when
    // processing the PONG.
    final PingPacketData mockPing =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet mockPacket = Packet.create(PacketType.PING, mockPing, keyPairs.get(0));
    doReturn(mockPacket).when(controller).createPacket(eq(PacketType.PING), any());
    controller.setRetryDelayFunction((prev) -> 999999999L);
    controller.start();

    // Verify that the PING was sent.
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.PING));

    // Simulate a PONG message from peer[0].
    final PongPacketData packetData =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet pongPacket = Packet.create(PacketType.PONG, packetData, keyPairs.get(0));
    controller.onMessage(pongPacket, peers.get(0));

    // Verify that the FIND_NEIGHBORS packet was sent with target == localPeer.
    final ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
    verify(outboundMessageHandler, atLeast(1)).send(eq(peers.get(0)), captor.capture());
    final List<Packet> neighborsPackets =
        captor
            .getAllValues()
            .stream()
            .filter(p -> p.getType().equals(PacketType.FIND_NEIGHBORS))
            .collect(Collectors.toList());
    assertThat(neighborsPackets.size()).isEqualTo(1);
    final Packet nieghborsPacket = neighborsPackets.get(0);
    final Optional<FindNeighborsPacketData> maybeData =
        nieghborsPacket.getPacketData(FindNeighborsPacketData.class);
    assertThat(maybeData).isPresent();
    final FindNeighborsPacketData data = maybeData.get();
    assertThat(data.getTarget()).isEqualTo(localPeer.getId());

    assertThat(controller.getPeers()).hasSize(1);
    assertThat(controller.getPeers().stream().findFirst().get().getStatus())
        .isEqualTo(PeerDiscoveryStatus.BONDED);
  }

  private ControllerBuilder getControllerBuilder() {
    return ControllerBuilder.create()
        .keyPair(localKeyPair)
        .localPeer(localPeer)
        .peerTable(peerTable);
  }

  @Test
  public void peerSeenTwice() throws InterruptedException {
    // Create three peers, out of which the first two are bootstrap peers.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(3);
    final List<DiscoveryPeer> peers = helper.createDiscoveryPeers(keyPairs);

    // Mock the creation of the PING packet, so that we can control the hash, which gets validated
    // when processing the PONG.
    final PingPacketData mockPing =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet mockPacket = Packet.create(PacketType.PING, mockPing, keyPairs.get(0));

    // Initialize the peer controller
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers.get(0), peers.get(1))
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    doReturn(mockPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.setRetryDelayFunction((prev) -> 999999999L);
    controller.start();

    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.PING));
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(1)), matchPacketOfType(PacketType.PING));

    // Simulate a PONG message from peer[0].
    final PongPacketData packetData0 =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet pongPacket0 = Packet.create(PacketType.PONG, packetData0, keyPairs.get(0));
    controller.onMessage(pongPacket0, peers.get(0));

    // Assert that we're bonding with the third peer.
    assertThat(controller.getPeers()).hasSize(2);
    assertThat(controller.getPeers())
        .filteredOn(p -> p.getStatus() == PeerDiscoveryStatus.BONDING)
        .hasSize(1);
    assertThat(controller.getPeers())
        .filteredOn(p -> p.getStatus() == PeerDiscoveryStatus.BONDED)
        .hasSize(1);

    final PongPacketData packetData1 =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet pongPacket1 = Packet.create(PacketType.PONG, packetData1, keyPairs.get(1));
    controller.onMessage(pongPacket1, peers.get(1));

    // Now after we got that pong we should have sent a find neighbours message...
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    // Simulate a NEIGHBORS message from peer[0] listing peer[2].
    final NeighborsPacketData neighbors0 =
        NeighborsPacketData.create(Collections.singletonList(peers.get(2)));
    final Packet neighborsPacket0 =
        Packet.create(PacketType.NEIGHBORS, neighbors0, keyPairs.get(0));
    controller.onMessage(neighborsPacket0, peers.get(0));

    // Assert that we're bonded with the third peer.
    assertThat(controller.getPeers()).hasSize(2);
    assertThat(controller.getPeers())
        .filteredOn(p -> p.getStatus() == PeerDiscoveryStatus.BONDED)
        .hasSize(2);

    // Simulate bonding and neighbors packet from the second bootstrap peer, with peer[2] reported
    // in
    // the peer list.
    final NeighborsPacketData neighbors1 =
        NeighborsPacketData.create(Collections.singletonList(peers.get(2)));
    final Packet neighborsPacket1 =
        Packet.create(PacketType.NEIGHBORS, neighbors1, keyPairs.get(1));
    controller.onMessage(neighborsPacket1, peers.get(1));

    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(2)), matchPacketOfType(PacketType.PING));

    // Send a PONG packet from peer[2], to transition it to the BONDED state.
    final PongPacketData packetData2 =
        PongPacketData.create(localPeer.getEndpoint(), mockPacket.getHash());
    final Packet pongPacket2 = Packet.create(PacketType.PONG, packetData2, keyPairs.get(2));
    controller.onMessage(pongPacket2, peers.get(2));

    // Assert we're now bonded with peer[2].
    assertThat(controller.getPeers())
        .filteredOn(p -> p.equals(peers.get(2)) && p.getStatus() == PeerDiscoveryStatus.BONDED)
        .hasSize(1);

    // Wait for 1 second and ensure that only 1 PING was ever sent to peer[2].
    Thread.sleep(1000);
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(2)), matchPacketOfType(PacketType.PING));
  }

  @Test(expected = IllegalStateException.class)
  public void startTwice() {
    startPeerDiscoveryController();
    controller.start();
  }

  @Test
  public void stopTwice() {
    startPeerDiscoveryController();
    controller.stop();
    controller.stop();
    // no exception
  }

  @Test
  public void shouldAddNewPeerWhenReceivedPingAndPeerTableBucketIsNotFull() {
    final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 1);
    startPeerDiscoveryController();

    final Packet pingPacket = mockPingPacket(peers.get(0), localPeer);
    controller.onMessage(pingPacket, peers.get(0));
    assertThat(controller.getPeers()).contains(peers.get(0));
  }

  @Test
  public void shouldNotAddSelfWhenReceivedPingFromSelf() {
    startPeerDiscoveryController();
    final DiscoveryPeer localPeer =
        new DiscoveryPeer(this.localPeer.getId(), this.localPeer.getEndpoint());

    final Packet pingPacket = mockPingPacket(this.localPeer, this.localPeer);
    controller.onMessage(pingPacket, localPeer);

    assertThat(controller.getPeers()).doesNotContain(localPeer);
  }

  @Test
  public void shouldAddNewPeerWhenReceivedPingAndPeerTableBucketIsFull() {
    final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 17);
    startPeerDiscoveryController();
    // Fill the last bucket.
    for (int i = 0; i < 16; i++) {
      peerTable.tryAdd(peers.get(i));
    }

    final Packet pingPacket = mockPingPacket(peers.get(16), localPeer);
    controller.onMessage(pingPacket, peers.get(16));

    assertThat(controller.getPeers()).contains(peers.get(16));
    // The first peer added should have been evicted.
    assertThat(controller.getPeers()).doesNotContain(peers.get(0));
  }

  @Test
  public void shouldNotRemoveExistingPeerWhenReceivedPing() {
    final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 1);
    startPeerDiscoveryController();

    peerTable.tryAdd(peers.get(0));
    assertThat(controller.getPeers()).contains(peers.get(0));

    final Packet pingPacket = mockPingPacket(peers.get(0), localPeer);
    controller.onMessage(pingPacket, peers.get(0));

    assertThat(controller.getPeers()).contains(peers.get(0));
  }

  @Test
  public void shouldNotAddNewPeerWhenReceivedPongFromBlacklistedPeer() {
    final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 3);

    // final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    //    final Bytes32 keccak256 = localPeer.keccak256();
    //    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    //    byte msb = keccak256.get(0);
    //    msb ^= MOST_SIGNFICANT_BIT_MASK;
    //    template.set(0, msb);

    /* * */

    //    template.setInt(template.size() - 4, 0);
    //    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    //    final MutableBytesValue id0 = MutableBytesValue.create(64);
    //    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    //    final DiscoveryPeer peer0 =
    //        new DiscoveryPeer(
    //            id0,
    //            new Endpoint(
    //                localPeer.getEndpoint().getHost(),
    //                100 + counter.incrementAndGet(),
    //                OptionalInt.empty()));
    //    peer0.setKeccak256(keccak0);
    //    peers.add(peer0);

    /* * */

    //    template.setInt(template.size() - 4, 0);
    //    final Bytes32 keccak1 = Bytes32.leftPad(template.copy());
    //    final MutableBytesValue id1 = MutableBytesValue.create(64);
    //    UInt256.of(1).getBytes().copyTo(id1, id1.size() - UInt256Value.SIZE);
    //    final DiscoveryPeer peer1 =
    //        new DiscoveryPeer(
    //            id1,
    //            new Endpoint(
    //                localPeer.getEndpoint().getHost(),
    //                100 + counter.incrementAndGet(),
    //                OptionalInt.empty()));
    //    peer1.setKeccak256(keccak1);
    //    peers.add(peer1);

    /* * */

    //    template.setInt(template.size() - 4, 0);
    //    final Bytes32 keccak2 = Bytes32.leftPad(template.copy());
    //    final MutableBytesValue id2 = MutableBytesValue.create(64);
    //    UInt256.of(2).getBytes().copyTo(id2, id2.size() - UInt256Value.SIZE);
    //    final DiscoveryPeer peer2 =
    //        new DiscoveryPeer(
    //            id2,
    //            new Endpoint(
    //                localPeer.getEndpoint().getHost(),
    //                100 + counter.incrementAndGet(),
    //                OptionalInt.empty()));
    //    peer2.setKeccak256(keccak2);
    //    peers.add(peer2);

    /* * */

    final DiscoveryPeer discoPeer = peers.get(0);
    final DiscoveryPeer otherPeer = peers.get(1);
    final DiscoveryPeer otherPeer2 = peers.get(2);

    final PeerBlacklist blacklist = new PeerBlacklist();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    final Endpoint localEndpoint = localPeer.getEndpoint();

    // Setup ping to be sent to discoPeer
    List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    PingPacketData pingPacketData = PingPacketData.create(localEndpoint, discoPeer.getEndpoint());
    final Packet discoPeerPing = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(discoPeerPing)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(discoPeer));

    controller.start();
    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.PING));

    final Packet pongFromDiscoPeer =
        MockPacketDataFactory.mockPongPacket(discoPeer, discoPeerPing.getHash());
    controller.onMessage(pongFromDiscoPeer, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    // Setup ping to be sent to otherPeer after neighbors packet is received
    keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    pingPacketData = PingPacketData.create(localEndpoint, otherPeer.getEndpoint());
    final Packet pingPacket = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(otherPeer));

    // Setup ping to be sent to otherPeer2 after neighbors packet is received
    keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    pingPacketData = PingPacketData.create(localEndpoint, otherPeer2.getEndpoint());
    final Packet pingPacket2 = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket2)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(otherPeer2));

    final Packet neighborsPacket =
        MockPacketDataFactory.mockNeighborsPacket(discoPeer, otherPeer, otherPeer2);
    controller.onMessage(neighborsPacket, discoPeer);

    verify(outboundMessageHandler, times(peers.size()))
        .send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongPacket = MockPacketDataFactory.mockPongPacket(otherPeer, pingPacket.getHash());
    controller.onMessage(pongPacket, otherPeer);

    // Blacklist otherPeer2 before sending return pong
    blacklist.add(otherPeer2);
    final Packet pongPacket2 =
        MockPacketDataFactory.mockPongPacket(otherPeer2, pingPacket2.getHash());
    controller.onMessage(pongPacket2, otherPeer2);

    assertThat(controller.getPeers()).hasSize(2);
    assertThat(controller.getPeers()).contains(discoPeer);
    assertThat(controller.getPeers()).contains(otherPeer);
    assertThat(controller.getPeers()).doesNotContain(otherPeer2);
  }

  private PacketData matchPingDataForPeer(final DiscoveryPeer peer) {
    return argThat((PacketData data) -> ((PingPacketData) data).getTo().equals(peer.getEndpoint()));
  }

  private Packet matchPacketOfType(final PacketType type) {
    return argThat((Packet packet) -> packet.getType().equals(type));
  }

  @Test
  public void shouldNotBondWithBlacklistedPeer()
      throws InterruptedException, ExecutionException, TimeoutException {

    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak1 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id1 = MutableBytesValue.create(64);
    UInt256.of(1).getBytes().copyTo(id1, id1.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer1 =
        new DiscoveryPeer(
            id1,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer1.setKeccak256(keccak1);
    peers.add(peer1);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak2 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id2 = MutableBytesValue.create(64);
    UInt256.of(2).getBytes().copyTo(id2, id2.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer2 =
        new DiscoveryPeer(
            id2,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer2.setKeccak256(keccak2);
    peers.add(peer2);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak3 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id3 = MutableBytesValue.create(64);
    UInt256.of(3).getBytes().copyTo(id3, id3.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer3 =
        new DiscoveryPeer(
            id3,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer3.setKeccak256(keccak3);
    peers.add(peer3);

    /* * */

    final DiscoveryPeer discoPeer = peers.get(0);
    final DiscoveryPeer otherPeer = peers.get(1);
    final DiscoveryPeer otherPeer2 = peers.get(2);

    final PeerBlacklist blacklist = new PeerBlacklist();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    final Endpoint localEndpoint = localPeer.getEndpoint();

    // Setup ping to be sent to discoPeer
    List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    PingPacketData pingPacketData = PingPacketData.create(localEndpoint, discoPeer.getEndpoint());
    final Packet discoPeerPing = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(discoPeerPing)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(discoPeer));

    controller.start();
    verify(outboundMessageHandler, times(1)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongFromDiscoPeer =
        MockPacketDataFactory.mockPongPacket(discoPeer, discoPeerPing.getHash());
    controller.onMessage(pongFromDiscoPeer, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    // Setup ping to be sent to otherPeer after neighbors packet is received
    keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    pingPacketData = PingPacketData.create(localEndpoint, otherPeer.getEndpoint());
    final Packet pingPacket = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(otherPeer));

    // Setup ping to be sent to otherPeer2 after neighbors packet is received
    keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    pingPacketData = PingPacketData.create(localEndpoint, otherPeer2.getEndpoint());
    final Packet pingPacket2 = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket2)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(otherPeer2));

    // Blacklist peer
    blacklist.add(otherPeer);

    final Packet neighborsPacket =
        MockPacketDataFactory.mockNeighborsPacket(discoPeer, otherPeer, otherPeer2);
    controller.onMessage(neighborsPacket, discoPeer);

    verify(controller, times(0)).bond(otherPeer);
    verify(controller, times(1)).bond(otherPeer2);
  }

  @Test
  public void shouldRespondToNeighborsRequestFromKnownPeer()
      throws InterruptedException, ExecutionException, TimeoutException {
    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    /* * */

    final DiscoveryPeer discoPeer = peers.get(0);

    final PeerBlacklist blacklist = new PeerBlacklist();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    final Endpoint localEndpoint = localPeer.getEndpoint();

    // Setup ping to be sent to discoPeer
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final PingPacketData pingPacketData =
        PingPacketData.create(localEndpoint, discoPeer.getEndpoint());
    final Packet discoPeerPing = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(discoPeerPing)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(discoPeer));

    controller.start();
    verify(outboundMessageHandler, times(1)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongFromDiscoPeer =
        MockPacketDataFactory.mockPongPacket(discoPeer, discoPeerPing.getHash());
    controller.onMessage(pongFromDiscoPeer, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    final Packet findNeighborsPacket = MockPacketDataFactory.mockFindNeighborsPacket(discoPeer);
    controller.onMessage(findNeighborsPacket, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.NEIGHBORS));
  }

  @Test
  public void shouldNotRespondToNeighborsRequestFromUnknownPeer()
      throws InterruptedException, ExecutionException, TimeoutException {
    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak1 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id1 = MutableBytesValue.create(64);
    UInt256.of(1).getBytes().copyTo(id1, id1.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer1 =
        new DiscoveryPeer(
            id1,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer1.setKeccak256(keccak1);
    peers.add(peer1);

    /* * */

    final DiscoveryPeer discoPeer = peers.get(0);
    final DiscoveryPeer otherPeer = peers.get(1);

    final PeerBlacklist blacklist = new PeerBlacklist();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    final Endpoint localEndpoint = localPeer.getEndpoint();

    // Setup ping to be sent to discoPeer
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final PingPacketData pingPacketData =
        PingPacketData.create(localEndpoint, discoPeer.getEndpoint());
    final Packet discoPeerPing = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(discoPeerPing)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(discoPeer));

    controller.start();
    verify(outboundMessageHandler, times(1)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongFromDiscoPeer =
        MockPacketDataFactory.mockPongPacket(discoPeer, discoPeerPing.getHash());
    controller.onMessage(pongFromDiscoPeer, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    final Packet findNeighborsPacket = MockPacketDataFactory.mockFindNeighborsPacket(discoPeer);
    controller.onMessage(findNeighborsPacket, otherPeer);

    verify(outboundMessageHandler, times(0))
        .send(eq(otherPeer), matchPacketOfType(PacketType.NEIGHBORS));
  }

  @Test
  public void shouldNotRespondToNeighborsRequestFromBlacklistedPeer() {
    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    final DiscoveryPeer discoPeer = peers.get(0);

    final PeerBlacklist blacklist = new PeerBlacklist();
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    final Endpoint localEndpoint = localPeer.getEndpoint();

    // Setup ping to be sent to discoPeer
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final PingPacketData pingPacketData =
        PingPacketData.create(localEndpoint, discoPeer.getEndpoint());
    final Packet discoPeerPing = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(discoPeerPing)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(discoPeer));

    controller.start();
    verify(outboundMessageHandler, times(1)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongFromDiscoPeer =
        MockPacketDataFactory.mockPongPacket(discoPeer, discoPeerPing.getHash());
    controller.onMessage(pongFromDiscoPeer, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    blacklist.add(discoPeer);
    final Packet findNeighborsPacket = MockPacketDataFactory.mockFindNeighborsPacket(discoPeer);
    controller.onMessage(findNeighborsPacket, discoPeer);

    verify(outboundMessageHandler, times(0))
        .send(eq(discoPeer), matchPacketOfType(PacketType.NEIGHBORS));
  }

  @Test
  public void shouldAddNewPeerWhenReceivedPongAndPeerTableBucketIsNotFull() {
    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    // Mock the creation of the PING packet to control hash for PONG.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final PingPacketData pingPacketData =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet pingPacket = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));

    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers.get(0))
            .outboundMessageHandler(outboundMessageHandler)
            .build();
    doReturn(pingPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.setRetryDelayFunction((prev) -> 999999999L);
    controller.start();

    verify(outboundMessageHandler, times(1)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongPacket =
        MockPacketDataFactory.mockPongPacket(peers.get(0), pingPacket.getHash());
    controller.onMessage(pongPacket, peers.get(0));

    assertThat(controller.getPeers()).contains(peers.get(0));
  }

  @Test
  public void shouldAddNewPeerWhenReceivedPongAndPeerTableBucketIsFull() {
    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak1 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id1 = MutableBytesValue.create(64);
    UInt256.of(1).getBytes().copyTo(id1, id1.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer1 =
        new DiscoveryPeer(
            id1,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer1.setKeccak256(keccak1);
    peers.add(peer1);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak2 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id2 = MutableBytesValue.create(64);
    UInt256.of(2).getBytes().copyTo(id2, id2.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer2 =
        new DiscoveryPeer(
            id2,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer2.setKeccak256(keccak2);
    peers.add(peer2);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak3 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id3 = MutableBytesValue.create(64);
    UInt256.of(3).getBytes().copyTo(id3, id3.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer3 =
        new DiscoveryPeer(
            id3,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer3.setKeccak256(keccak3);
    peers.add(peer3);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak4 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id4 = MutableBytesValue.create(64);
    UInt256.of(4).getBytes().copyTo(id4, id4.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer4 =
        new DiscoveryPeer(
            id4,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer4.setKeccak256(keccak4);
    peers.add(peer4);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak5 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id5 = MutableBytesValue.create(64);
    UInt256.of(5).getBytes().copyTo(id5, id5.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer5 =
        new DiscoveryPeer(
            id5,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                155 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer5.setKeccak256(keccak5);
    peers.add(peer5);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak6 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id6 = MutableBytesValue.create(64);
    UInt256.of(6).getBytes().copyTo(id6, id6.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer6 =
        new DiscoveryPeer(
            id6,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                600 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer6.setKeccak256(keccak6);
    peers.add(peer6);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak7 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id7 = MutableBytesValue.create(64);
    UInt256.of(7).getBytes().copyTo(id7, id7.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer7 =
        new DiscoveryPeer(
            id7,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer7.setKeccak256(keccak7);
    peers.add(peer7);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak8 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id8 = MutableBytesValue.create(64);
    UInt256.of(8).getBytes().copyTo(id8, id8.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer8 =
        new DiscoveryPeer(
            id8,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer8.setKeccak256(keccak8);
    peers.add(peer8);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak9 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id9 = MutableBytesValue.create(64);
    UInt256.of(9).getBytes().copyTo(id9, id9.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer9 =
        new DiscoveryPeer(
            id9,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer9.setKeccak256(keccak9);
    peers.add(peer9);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak10 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id10 = MutableBytesValue.create(64);
    UInt256.of(10).getBytes().copyTo(id10, id10.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer10 =
        new DiscoveryPeer(
            id10,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer10.setKeccak256(keccak10);
    peers.add(peer10);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak11 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id11 = MutableBytesValue.create(64);
    UInt256.of(11).getBytes().copyTo(id11, id11.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer11 =
        new DiscoveryPeer(
            id11,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer11.setKeccak256(keccak11);
    peers.add(peer11);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak12 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id12 = MutableBytesValue.create(64);
    UInt256.of(12).getBytes().copyTo(id12, id12.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer12 =
        new DiscoveryPeer(
            id12,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer12.setKeccak256(keccak12);
    peers.add(peer12);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak13 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id13 = MutableBytesValue.create(64);
    UInt256.of(13).getBytes().copyTo(id13, id13.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer13 =
        new DiscoveryPeer(
            id13,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer13.setKeccak256(keccak13);
    peers.add(peer13);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak14 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id14 = MutableBytesValue.create(64);
    UInt256.of(14).getBytes().copyTo(id14, id14.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer14 =
        new DiscoveryPeer(
            id14,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer14.setKeccak256(keccak14);
    peers.add(peer14);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak15 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id15 = MutableBytesValue.create(64);
    UInt256.of(15).getBytes().copyTo(id15, id15.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer15 =
        new DiscoveryPeer(
            id15,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer15.setKeccak256(keccak15);
    peers.add(peer15);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak16 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id16 = MutableBytesValue.create(64);
    UInt256.of(16).getBytes().copyTo(id16, id16.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer16 =
        new DiscoveryPeer(
            id16,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer16.setKeccak256(keccak16);
    peers.add(peer16);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak17 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id17 = MutableBytesValue.create(64);
    UInt256.of(17).getBytes().copyTo(id17, id17.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer17 =
        new DiscoveryPeer(
            id17,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer17.setKeccak256(keccak17);
    peers.add(peer17);

    /* * */

    final List<DiscoveryPeer> bootstrapPeers = peers.subList(0, 16);
    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(bootstrapPeers)
            .outboundMessageHandler(outboundMessageHandler)
            .build();
    controller.setRetryDelayFunction(LONG_DELAY_FUNCTION);

    // Mock the creation of PING packets to control hash PONG packets.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final PingPacketData pingPacketData =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet pingPacket = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket).when(controller).createPacket(eq(PacketType.PING), any());

    controller.start();

    verify(outboundMessageHandler, times(16)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongPacket0 =
        MockPacketDataFactory.mockPongPacket(peers.get(0), pingPacket.getHash());
    controller.onMessage(pongPacket0, peers.get(0));

    final Packet pongPacket1 =
        MockPacketDataFactory.mockPongPacket(peers.get(1), pingPacket.getHash());
    controller.onMessage(pongPacket1, peers.get(1));

    final Packet pongPacket2 =
        MockPacketDataFactory.mockPongPacket(peers.get(2), pingPacket.getHash());
    controller.onMessage(pongPacket2, peers.get(2));

    final Packet pongPacket3 =
        MockPacketDataFactory.mockPongPacket(peers.get(3), pingPacket.getHash());
    controller.onMessage(pongPacket3, peers.get(3));

    final Packet pongPacket4 =
        MockPacketDataFactory.mockPongPacket(peers.get(4), pingPacket.getHash());
    controller.onMessage(pongPacket4, peers.get(4));

    final Packet pongPacket5 =
        MockPacketDataFactory.mockPongPacket(peers.get(5), pingPacket.getHash());
    controller.onMessage(pongPacket5, peers.get(5));

    final Packet pongPacket6 =
        MockPacketDataFactory.mockPongPacket(peers.get(6), pingPacket.getHash());
    controller.onMessage(pongPacket6, peers.get(6));

    final Packet pongPacket7 =
        MockPacketDataFactory.mockPongPacket(peers.get(7), pingPacket.getHash());
    controller.onMessage(pongPacket7, peers.get(7));

    final Packet pongPacket8 =
        MockPacketDataFactory.mockPongPacket(peers.get(8), pingPacket.getHash());
    controller.onMessage(pongPacket8, peers.get(8));

    final Packet pongPacket9 =
        MockPacketDataFactory.mockPongPacket(peers.get(9), pingPacket.getHash());
    controller.onMessage(pongPacket9, peers.get(9));

    final Packet pongPacket10 =
        MockPacketDataFactory.mockPongPacket(peers.get(10), pingPacket.getHash());
    controller.onMessage(pongPacket10, peers.get(10));

    final Packet pongPacket11 =
        MockPacketDataFactory.mockPongPacket(peers.get(11), pingPacket.getHash());
    controller.onMessage(pongPacket11, peers.get(11));

    final Packet pongPacket12 =
        MockPacketDataFactory.mockPongPacket(peers.get(12), pingPacket.getHash());
    controller.onMessage(pongPacket12, peers.get(12));

    final Packet pongPacket13 =
        MockPacketDataFactory.mockPongPacket(peers.get(13), pingPacket.getHash());
    controller.onMessage(pongPacket13, peers.get(13));

    final Packet pongPacket14 =
        MockPacketDataFactory.mockPongPacket(peers.get(14), pingPacket.getHash());
    controller.onMessage(pongPacket14, peers.get(14));

    verify(outboundMessageHandler, times(0))
        .send(any(), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    final Packet pongPacket15 =
        MockPacketDataFactory.mockPongPacket(peers.get(15), pingPacket.getHash());
    controller.onMessage(pongPacket15, peers.get(15));

    verify(outboundMessageHandler, times(3))
        .send(any(), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    final Packet neighborsPacket0 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(0), peers.get(16));
    controller.onMessage(neighborsPacket0, peers.get(0));

    final Packet neighborsPacket1 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(1), peers.get(16));
    controller.onMessage(neighborsPacket1, peers.get(1));

    final Packet neighborsPacket2 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(2), peers.get(16));
    controller.onMessage(neighborsPacket2, peers.get(2));

    final Packet neighborsPacket3 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(3), peers.get(16));
    controller.onMessage(neighborsPacket3, peers.get(3));

    final Packet neighborsPacket4 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(4), peers.get(16));
    controller.onMessage(neighborsPacket4, peers.get(4));

    final Packet neighborsPacket5 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(5), peers.get(16));
    controller.onMessage(neighborsPacket5, peers.get(5));

    final Packet neighborsPacket6 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(6), peers.get(16));
    controller.onMessage(neighborsPacket6, peers.get(6));

    final Packet neighborsPacket7 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(7), peers.get(16));
    controller.onMessage(neighborsPacket7, peers.get(7));

    final Packet neighborsPacket8 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(8), peers.get(16));
    controller.onMessage(neighborsPacket8, peers.get(8));

    final Packet neighborsPacket9 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(9), peers.get(16));
    controller.onMessage(neighborsPacket9, peers.get(9));

    final Packet neighborsPacket10 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(10), peers.get(16));
    controller.onMessage(neighborsPacket10, peers.get(10));

    final Packet neighborsPacket11 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(11), peers.get(16));
    controller.onMessage(neighborsPacket11, peers.get(11));

    final Packet neighborsPacket12 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(12), peers.get(16));
    controller.onMessage(neighborsPacket12, peers.get(12));

    final Packet neighborsPacket13 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(13), peers.get(16));
    controller.onMessage(neighborsPacket13, peers.get(13));

    final Packet neighborsPacket14 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(14), peers.get(16));
    controller.onMessage(neighborsPacket14, peers.get(14));

    final Packet neighborsPacket15 =
        MockPacketDataFactory.mockNeighborsPacket(peers.get(15), peers.get(16));
    controller.onMessage(neighborsPacket15, peers.get(15));

    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(16)), matchPacketOfType(PacketType.PING));

    final Packet pongPacket16 =
        MockPacketDataFactory.mockPongPacket(peers.get(16), pingPacket.getHash());
    controller.onMessage(pongPacket16, peers.get(16));

    assertThat(controller.getPeers()).contains(peers.get(16));
    assertThat(controller.getPeers().size()).isEqualTo(16);
    assertThat(ausbooten(bootstrapPeers, controller)).isTrue();
  }

  @SuppressWarnings("SpellCheckingInspection")
  private boolean ausbooten(
      final List<DiscoveryPeer> peers, final PeerDiscoveryController controller) {
    for (final DiscoveryPeer peer : peers) {
      if (!controller.getPeers().contains(peer)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void shouldNotAddPeerInNeighborsPacketWithoutBonding() {
    // final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 2);

    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak1 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id1 = MutableBytesValue.create(64);
    UInt256.of(1).getBytes().copyTo(id1, id1.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer1 =
        new DiscoveryPeer(
            id1,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer1.setKeccak256(keccak1);
    peers.add(peer1);

    // Mock the creation of the PING packet to control hash for PONG.
    final List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    final PingPacketData pingPacketData =
        PingPacketData.create(localPeer.getEndpoint(), peers.get(0).getEndpoint());
    final Packet pingPacket = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));

    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(peers.get(0))
            .outboundMessageHandler(outboundMessageHandler)
            .build();
    doReturn(pingPacket).when(controller).createPacket(eq(PacketType.PING), any());
    controller.start();

    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.PING));

    final Packet pongPacket =
        MockPacketDataFactory.mockPongPacket(peers.get(0), pingPacket.getHash());
    controller.onMessage(pongPacket, peers.get(0));

    verify(outboundMessageHandler, times(1))
        .send(eq(peers.get(0)), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    assertThat(controller.getPeers()).doesNotContain(peers.get(1));
  }

  @Test
  public void shouldNotBondWithNonWhitelistedPeer()
      throws InterruptedException, ExecutionException, TimeoutException {
    // final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 3);

    final List<DiscoveryPeer> peers = new ArrayList<>();

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = localPeer.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak0 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id0 = MutableBytesValue.create(64);
    UInt256.of(0).getBytes().copyTo(id0, id0.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer0 =
        new DiscoveryPeer(
            id0,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer0.setKeccak256(keccak0);
    peers.add(peer0);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak1 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id1 = MutableBytesValue.create(64);
    UInt256.of(1).getBytes().copyTo(id1, id1.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer1 =
        new DiscoveryPeer(
            id1,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer1.setKeccak256(keccak1);
    peers.add(peer1);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak2 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id2 = MutableBytesValue.create(64);
    UInt256.of(2).getBytes().copyTo(id2, id2.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer2 =
        new DiscoveryPeer(
            id2,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer2.setKeccak256(keccak2);
    peers.add(peer2);

    /* * */

    template.setInt(template.size() - 4, 0);
    final Bytes32 keccak3 = Bytes32.leftPad(template.copy());
    final MutableBytesValue id3 = MutableBytesValue.create(64);
    UInt256.of(3).getBytes().copyTo(id3, id3.size() - UInt256Value.SIZE);
    final DiscoveryPeer peer3 =
        new DiscoveryPeer(
            id3,
            new Endpoint(
                localPeer.getEndpoint().getHost(),
                100 + counter.incrementAndGet(),
                OptionalInt.empty()));
    peer3.setKeccak256(keccak3);
    peers.add(peer3);

    /* * */

    final DiscoveryPeer discoPeer = peers.get(0);
    final DiscoveryPeer otherPeer = peers.get(1);
    final DiscoveryPeer otherPeer2 = peers.get(2);

    final PeerBlacklist blacklist = new PeerBlacklist();
    final PermissioningConfiguration config = new PermissioningConfiguration();
    final NodeWhitelistController nodeWhitelistController = new NodeWhitelistController(config);

    // Whitelist peers
    nodeWhitelistController.addNode(discoPeer);
    nodeWhitelistController.addNode(otherPeer2);

    final OutboundMessageHandler outboundMessageHandler = mock(OutboundMessageHandler.class);
    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .whitelist(nodeWhitelistController)
            .outboundMessageHandler(outboundMessageHandler)
            .build();

    final Endpoint localEndpoint = localPeer.getEndpoint();

    // Setup ping to be sent to discoPeer
    List<SECP256K1.KeyPair> keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    PingPacketData pingPacketData = PingPacketData.create(localEndpoint, discoPeer.getEndpoint());
    final Packet discoPeerPing = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(discoPeerPing)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(discoPeer));

    controller.start();
    verify(outboundMessageHandler, times(1)).send(any(), matchPacketOfType(PacketType.PING));

    final Packet pongFromDiscoPeer =
        MockPacketDataFactory.mockPongPacket(discoPeer, discoPeerPing.getHash());
    controller.onMessage(pongFromDiscoPeer, discoPeer);

    verify(outboundMessageHandler, times(1))
        .send(eq(discoPeer), matchPacketOfType(PacketType.FIND_NEIGHBORS));

    // Setup ping to be sent to otherPeer after neighbors packet is received
    keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    pingPacketData = PingPacketData.create(localEndpoint, otherPeer.getEndpoint());
    final Packet pingPacket = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(otherPeer));

    // Setup ping to be sent to otherPeer2 after neighbors packet is received
    keyPairs = PeerDiscoveryTestHelper.generateKeyPairs(1);
    pingPacketData = PingPacketData.create(localEndpoint, otherPeer2.getEndpoint());
    final Packet pingPacket2 = Packet.create(PacketType.PING, pingPacketData, keyPairs.get(0));
    doReturn(pingPacket2)
        .when(controller)
        .createPacket(eq(PacketType.PING), matchPingDataForPeer(otherPeer2));

    final Packet neighborsPacket =
        MockPacketDataFactory.mockNeighborsPacket(discoPeer, otherPeer, otherPeer2);
    controller.onMessage(neighborsPacket, discoPeer);

    verify(controller, times(0)).bond(otherPeer);
    verify(controller, times(1)).bond(otherPeer2);
  }

  @Test
  public void shouldNotRespondToPingFromNonWhitelistedDiscoveryPeer() {
    final List<DiscoveryPeer> peers = createPeersInLastBucket(localPeer, 3);
    final DiscoveryPeer discoPeer = peers.get(0);

    final PeerBlacklist blacklist = new PeerBlacklist();

    // don't add disco peer to whitelist
    final PermissioningConfiguration config = PermissioningConfiguration.createDefault();
    config.setNodeWhitelist(new ArrayList<>());
    final NodeWhitelistController nodeWhitelistController = new NodeWhitelistController(config);

    controller =
        getControllerBuilder()
            .peers(discoPeer)
            .blacklist(blacklist)
            .whitelist(nodeWhitelistController)
            .build();

    final Packet pingPacket = mockPingPacket(peers.get(0), localPeer);
    controller.onMessage(pingPacket, peers.get(0));
    assertThat(controller.getPeers()).doesNotContain(peers.get(0));
  }

  private static Packet mockPingPacket(final Peer from, final Peer to) {
    final Packet packet = mock(Packet.class);

    final PingPacketData pingPacketData =
        PingPacketData.create(from.getEndpoint(), to.getEndpoint());
    when(packet.getPacketData(any())).thenReturn(Optional.of(pingPacketData));
    final BytesValue id = from.getId();
    when(packet.getNodeId()).thenReturn(id);
    when(packet.getType()).thenReturn(PacketType.PING);
    when(packet.getHash()).thenReturn(Bytes32.ZERO);

    return packet;
  }

  private List<DiscoveryPeer> createPeersInLastBucket(final Peer host, final int n) {
    final List<DiscoveryPeer> newPeers = new ArrayList<DiscoveryPeer>(n);

    // Flipping the most significant bit of the keccak256 will place the peer
    // in the last bucket for the corresponding host peer.
    final Bytes32 keccak256 = host.keccak256();
    final MutableBytesValue template = MutableBytesValue.create(keccak256.size());
    byte msb = keccak256.get(0);
    msb ^= MOST_SIGNFICANT_BIT_MASK;
    template.set(0, msb);

    for (int i = 0; i < n; i++) {
      template.setInt(template.size() - 4, i);
      final Bytes32 keccak = Bytes32.leftPad(template.copy());
      final MutableBytesValue id = MutableBytesValue.create(64);
      UInt256.of(i).getBytes().copyTo(id, id.size() - UInt256Value.SIZE);
      final DiscoveryPeer peer =
          new DiscoveryPeer(
              id,
              new Endpoint(
                  localPeer.getEndpoint().getHost(),
                  100 + counter.incrementAndGet(),
                  OptionalInt.empty()));
      peer.setKeccak256(keccak);
      newPeers.add(peer);
      //      template.setInt(template.size() - 4, i);
      //      final Bytes32 newKeccak256 = Bytes32.leftPad(template.copy());
      //      final DiscoveryPeer newPeer = mock(DiscoveryPeer.class);
      //      when(newPeer.keccak256()).thenReturn(newKeccak256);
      //      final MutableBytesValue newId = MutableBytesValue.create(64);
      //      UInt256.of(i).getBytes().copyTo(newId, newId.size() - UInt256Value.SIZE);
      //      when(newPeer.getId()).thenReturn(newId);
      //      when(newPeer.getEndpoint())
      //          .thenReturn(
      //              new Endpoint(
      //                  host.getEndpoint().getHost(),
      //                  100 + counter.incrementAndGet(),
      //                  OptionalInt.empty()));
      //      newPeers.add(newPeer);
    }

    return newPeers;
  }

  private PeerDiscoveryController startPeerDiscoveryController(
      final DiscoveryPeer... bootstrapPeers) {
    return startPeerDiscoveryController(LONG_DELAY_FUNCTION, bootstrapPeers);
  }

  private PeerDiscoveryController startPeerDiscoveryController(
      final RetryDelayFunction retryDelayFunction, final DiscoveryPeer... bootstrapPeers) {
    // Create the controller.
    controller = getControllerBuilder().peers(bootstrapPeers).build();
    controller.setRetryDelayFunction(retryDelayFunction);
    controller.start();
    return controller;
  }

  static class ControllerBuilder {
    private Collection<DiscoveryPeer> discoPeers = Collections.emptyList();
    private PeerBlacklist blacklist = new PeerBlacklist();
    private NodeWhitelistController whitelist =
        new NodeWhitelistController(PermissioningConfiguration.createDefault());
    private MockTimerUtil timerUtil = new MockTimerUtil();
    private KeyPair keypair;
    private DiscoveryPeer localPeer;
    private PeerTable peerTable;
    private OutboundMessageHandler outboundMessageHandler = OutboundMessageHandler.NOOP;
    private static final PeerDiscoveryTestHelper helper = new PeerDiscoveryTestHelper();

    public static ControllerBuilder create() {
      return new ControllerBuilder();
    }

    ControllerBuilder peers(final Collection<DiscoveryPeer> discoPeers) {
      this.discoPeers = discoPeers;
      return this;
    }

    ControllerBuilder peers(final DiscoveryPeer... discoPeers) {
      this.discoPeers = Arrays.asList(discoPeers);
      return this;
    }

    ControllerBuilder blacklist(final PeerBlacklist blacklist) {
      this.blacklist = blacklist;
      return this;
    }

    ControllerBuilder whitelist(final NodeWhitelistController whitelist) {
      this.whitelist = whitelist;
      return this;
    }

    ControllerBuilder timerUtil(final MockTimerUtil timerUtil) {
      this.timerUtil = timerUtil;
      return this;
    }

    ControllerBuilder keyPair(final KeyPair keypair) {
      this.keypair = keypair;
      return this;
    }

    ControllerBuilder localPeer(final DiscoveryPeer localPeer) {
      this.localPeer = localPeer;
      return this;
    }

    ControllerBuilder peerTable(final PeerTable peerTable) {
      this.peerTable = peerTable;
      return this;
    }

    ControllerBuilder outboundMessageHandler(final OutboundMessageHandler outboundMessageHandler) {
      this.outboundMessageHandler = outboundMessageHandler;
      return this;
    }

    PeerDiscoveryController build() {
      checkNotNull(keypair);
      if (localPeer == null) {
        localPeer = helper.createDiscoveryPeer(keypair);
      }
      if (peerTable == null) {
        peerTable = new PeerTable(localPeer.getId());
      }
      return spy(
          new PeerDiscoveryController(
              keypair,
              localPeer,
              peerTable,
              discoPeers,
              outboundMessageHandler,
              timerUtil,
              TABLE_REFRESH_INTERVAL_MS,
              PEER_REQUIREMENT,
              blacklist,
              whitelist,
              new Subscribers<>()));
    }
  }
}
