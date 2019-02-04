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
package tech.pegasys.pantheon.ethereum.p2p.discovery.internal;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryStatus;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.RecursivePeerRefreshState.BondingAgent;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.RecursivePeerRefreshState.FindNeighbourDispatcher;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class RecursivePeerRefreshState00 {
  private static final BytesValue TARGET = createId(0);
  private final PeerBlacklist peerBlacklist = mock(PeerBlacklist.class);
  private final NodeWhitelistController peerWhitelist = mock(NodeWhitelistController.class);
  private final BondingAgent bondingAgent = mock(BondingAgent.class);
  private final FindNeighbourDispatcher neighborFinder = mock(FindNeighbourDispatcher.class);
  private final MockTimerUtil timerUtil = new MockTimerUtil();

  private final DiscoveryPeer peer1 = new DiscoveryPeer(createId(1), "127.0.0.1", 1, 1);
  private final DiscoveryPeer peer2 = new DiscoveryPeer(createId(2), "127.0.0.2", 2, 2);
  private final DiscoveryPeer peer3 = new DiscoveryPeer(createId(3), "127.0.0.3", 3, 3);

  private final RecursivePeerRefreshState recursivePeerRefreshState =
      new RecursivePeerRefreshState(
          peerBlacklist, peerWhitelist, bondingAgent, neighborFinder, timerUtil, 5);

  private DiscoveryPeer peer_000 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x11111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111111"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_010 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_020 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_021 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_022 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_023 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_011 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_120 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_121 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_122 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_123 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_012 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_220 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_221 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_222 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_223 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_013 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_320 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_321 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000100000000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_322 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000000"),
          "0.0.0.0",
          1,
          1);
  private DiscoveryPeer peer_323 =
      new DiscoveryPeer(
          BytesValue.fromHexString(
              "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001"),
          "0.0.0.0",
          1,
          1);

    List<DiscoveryPeer> peerTable_000 = Arrays.asList(peer_010, peer_011, peer_012, peer_013);
    List<DiscoveryPeer> peerTable_010 = Arrays.asList(peer_020, peer_021, peer_022, peer_023);
    List<DiscoveryPeer> peerTable_011 = Arrays.asList(peer_120, peer_121, peer_122, peer_123);
    List<DiscoveryPeer> peerTable_012 = Arrays.asList(peer_220, peer_221, peer_222, peer_223);
    List<DiscoveryPeer> peerTable_013 = Arrays.asList(peer_320, peer_321, peer_322, peer_323);

    private NeighborsPacketData neighborsPacketData_000 = NeighborsPacketData.create(peerTable_000);
    private NeighborsPacketData neighborsPacketData_010 = NeighborsPacketData.create(peerTable_010);
    private NeighborsPacketData neighborsPacketData_011 = NeighborsPacketData.create(peerTable_011);
    private NeighborsPacketData neighborsPacketData_012 = NeighborsPacketData.create(peerTable_012);
    private NeighborsPacketData neighborsPacketData_013 = NeighborsPacketData.create(peerTable_013);

  @Before
  public void setUp() {
    when(peerWhitelist.contains(any(DiscoveryPeer.class))).thenReturn(true);
  }

  @Test
  public void shouldBondWithInitialNodesWhenStarted() {
    recursivePeerRefreshState.start(asList(peer1, peer2, peer3), TARGET);

    verify(bondingAgent).performBonding(peer1);
    verify(bondingAgent).performBonding(peer2);
    verify(bondingAgent).performBonding(peer3);
    verifyNoMoreInteractions(bondingAgent, neighborFinder);
  }

  @Test
  public void shouldOnlyBondWithUnbondedInitialNodes() {}

  @Test
  public void shouldSkipStraightToFindNeighboursIfAllInitialNodesAreBonded() {
    peer1.setStatus(PeerDiscoveryStatus.BONDED);
    peer2.setStatus(PeerDiscoveryStatus.BONDED);

    recursivePeerRefreshState.start(asList(peer1, peer2), TARGET);

    verify(neighborFinder).findNeighbours(peer1, TARGET);
    verify(neighborFinder).findNeighbours(peer2, TARGET);

    verifyNoMoreInteractions(bondingAgent, neighborFinder);
  }

  @Test
  public void shouldBondWithNewlyDiscoveredNodes() {
    peer1.setStatus(PeerDiscoveryStatus.BONDED);

    recursivePeerRefreshState.start(singletonList(peer1), TARGET);

    verify(neighborFinder).findNeighbours(peer1, TARGET);
    recursivePeerRefreshState.onNeighboursPacketReceived(
        peer1, NeighborsPacketData.create(asList(peer2, peer3)));

    verify(bondingAgent).performBonding(peer2);
    verify(bondingAgent).performBonding(peer3);

    verifyNoMoreInteractions(bondingAgent, neighborFinder);
  }

  @Test
  public void shouldMoveToNeighboursRoundWhenBondingTimesOut() {
    peer1.setStatus(PeerDiscoveryStatus.BONDED);
    recursivePeerRefreshState.start(asList(peer1, peer2), TARGET);

    verify(bondingAgent).performBonding(peer2);
    timerUtil.runTimerHandlers();

    verify(neighborFinder).findNeighbours(peer1, TARGET);

    verifyNoMoreInteractions(bondingAgent, neighborFinder);
  }

  @Test
  public void shouldMoveToBondingRoundWhenNeighboursRoundTimesOut() {}

  @Test
  public void shouldStopWhenAllNodesHaveBeenQueried() {}

  @Test
  public void shouldStopWhenMaximumNumberOfRoundsReached() {}

  @Test
  public void shouldOnlyQueryClosestThreeNeighbours() {}

  @Test
  public void shouldNotQueryNodeThatIsAlreadyQueried() {}

  @Test
  public void shouldBondWithNewNeighboursWhenSomeRequestsTimeOut() {
      recursivePeerRefreshState.start(singletonList(peer_000), TARGET);

      verify(bondingAgent).performBonding(peer_000);

      peer_000.setStatus(PeerDiscoveryStatus.BONDED);

      recursivePeerRefreshState.onBondingComplete(peer_000);

      verify(neighborFinder).findNeighbours(peer_000, TARGET);

      recursivePeerRefreshState.onNeighboursPacketReceived(peer_000, neighborsPacketData_000);

      peer_010.setStatus(PeerDiscoveryStatus.BONDED);
      peer_011.setStatus(PeerDiscoveryStatus.BONDED);
      peer_012.setStatus(PeerDiscoveryStatus.BONDED);
      peer_013.setStatus(PeerDiscoveryStatus.BONDED);

      recursivePeerRefreshState.onBondingComplete(peer_010); // Trigger the next round...

      verify(neighborFinder, never()).findNeighbours(peer_010, TARGET);
      verify(neighborFinder).findNeighbours(peer_011, TARGET);
      verify(neighborFinder).findNeighbours(peer_012, TARGET);
      verify(neighborFinder).findNeighbours(peer_013, TARGET);

      timerUtil.runTimerHandlers();

      verify(neighborFinder).findNeighbours(peer_010, TARGET);
  }

  @Test
  public void shouldNotBondWithDiscoveredNodesThatAreAlreadyBonded() {}

  @Test
  public void shouldQueryNodeThatTimedOutWithBondingButLaterCompletedBonding() {}

  @Test
  public void shouldBondWithPeersInNeighboursResponseReceivedAfterTimeout() {}

  @Test
  public void shouldNotBondWithNodesOnBlacklist() {}

  @Test
  public void shouldNotBondWithNodesRejectedByWhitelist() {
    reset(peerWhitelist);
  }

  private static BytesValue createId(final int id) {
    return BytesValue.fromHexString(String.format("%0128x", id));
  }
}
