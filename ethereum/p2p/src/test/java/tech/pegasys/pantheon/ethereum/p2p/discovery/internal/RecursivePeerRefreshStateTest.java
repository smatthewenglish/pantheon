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

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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

import org.junit.Before;
import org.junit.Test;

public class RecursivePeerRefreshStateTest {

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
  public void shouldBondWithNewNeighboursWhenSomeRequestsTimeOut() {}

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
