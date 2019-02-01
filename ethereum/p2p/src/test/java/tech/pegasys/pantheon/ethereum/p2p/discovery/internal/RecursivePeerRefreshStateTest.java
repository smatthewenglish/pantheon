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
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
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
import tech.pegasys.pantheon.ethereum.p2p.peers.Endpoint;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

public class RecursivePeerRefreshStateTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

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

  private final List<TestPeer> aggregatePeerList = new ArrayList<>();

  private NeighborsPacketData neighborsPacketData_000;
  private NeighborsPacketData neighborsPacketData_010;
  private NeighborsPacketData neighborsPacketData_011;
  private NeighborsPacketData neighborsPacketData_012;
  private NeighborsPacketData neighborsPacketData_013;

  private TestPeer peer_000;
  private TestPeer peer_010;
  private TestPeer peer_020;
  private TestPeer peer_021;
  private TestPeer peer_022;
  private TestPeer peer_023;
  private TestPeer peer_011;
  private TestPeer peer_120;
  private TestPeer peer_121;
  private TestPeer peer_122;
  private TestPeer peer_123;
  private TestPeer peer_012;
  private TestPeer peer_220;
  private TestPeer peer_221;
  private TestPeer peer_222;
  private TestPeer peer_223;
  private TestPeer peer_013;
  private TestPeer peer_320;
  private TestPeer peer_321;
  private TestPeer peer_322;
  private TestPeer peer_323;

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
  public void shouldOnlyQueryClosestThreeNeighbours() {
    setupRelativePeerTree();

    recursivePeerRefreshState.start(singletonList(peer_000), TARGET);

    verify(bondingAgent).performBonding(peer_000);

    peer_000.setStatus(PeerDiscoveryStatus.BONDED);

    recursivePeerRefreshState.onBondingComplete(peer_000);

    verify(neighborFinder).findNeighbours(peer_000, TARGET);

    recursivePeerRefreshState.onNeighboursPacketReceived(peer_000, neighborsPacketData_000);

    verify(bondingAgent).performBonding(peer_010);
    verify(bondingAgent).performBonding(peer_011);
    verify(bondingAgent).performBonding(peer_012);
    verify(bondingAgent).performBonding(peer_013);

    peer_010.setStatus(PeerDiscoveryStatus.BONDED);
    peer_011.setStatus(PeerDiscoveryStatus.BONDED);
    peer_012.setStatus(PeerDiscoveryStatus.BONDED);
    peer_013.setStatus(PeerDiscoveryStatus.BONDED);

    recursivePeerRefreshState.onBondingComplete(peer_010); // Trigger the next round...

    verify(neighborFinder, never()).findNeighbours(peer_010, TARGET);
    verify(neighborFinder).findNeighbours(peer_011, TARGET);
    verify(neighborFinder).findNeighbours(peer_012, TARGET);
    verify(neighborFinder).findNeighbours(peer_013, TARGET);

    recursivePeerRefreshState.onNeighboursPacketReceived(peer_011, neighborsPacketData_011);
    recursivePeerRefreshState.onNeighboursPacketReceived(peer_012, neighborsPacketData_012);
    recursivePeerRefreshState.onNeighboursPacketReceived(peer_013, neighborsPacketData_013);

    verify(bondingAgent).performBonding(peer_120);
    verify(bondingAgent).performBonding(peer_121);
    verify(bondingAgent).performBonding(peer_122);
    verify(bondingAgent).performBonding(peer_123);

    verify(bondingAgent).performBonding(peer_220);
    verify(bondingAgent).performBonding(peer_221);
    verify(bondingAgent).performBonding(peer_222);
    verify(bondingAgent).performBonding(peer_223);

    verify(bondingAgent).performBonding(peer_320);
    verify(bondingAgent).performBonding(peer_321);
    verify(bondingAgent).performBonding(peer_322);
    verify(bondingAgent).performBonding(peer_323);

    peer_120.setStatus(PeerDiscoveryStatus.BONDED);
    peer_121.setStatus(PeerDiscoveryStatus.BONDED);
    peer_122.setStatus(PeerDiscoveryStatus.BONDED);
    peer_123.setStatus(PeerDiscoveryStatus.BONDED);
    peer_220.setStatus(PeerDiscoveryStatus.BONDED);
    peer_221.setStatus(PeerDiscoveryStatus.BONDED);
    peer_222.setStatus(PeerDiscoveryStatus.BONDED);
    peer_223.setStatus(PeerDiscoveryStatus.BONDED);
    peer_320.setStatus(PeerDiscoveryStatus.BONDED);
    peer_321.setStatus(PeerDiscoveryStatus.BONDED);
    peer_322.setStatus(PeerDiscoveryStatus.BONDED);
    peer_323.setStatus(PeerDiscoveryStatus.BONDED);

    recursivePeerRefreshState.onBondingComplete(peer_323);

    verify(neighborFinder).findNeighbours(peer_120, TARGET);
    verify(neighborFinder).findNeighbours(peer_121, TARGET);
    verify(neighborFinder).findNeighbours(peer_323, TARGET);

    verify(neighborFinder, never()).findNeighbours(peer_122, TARGET);
    verify(neighborFinder, never()).findNeighbours(peer_123, TARGET);

    verify(neighborFinder, never()).findNeighbours(peer_220, TARGET);
    verify(neighborFinder, never()).findNeighbours(peer_221, TARGET);
    verify(neighborFinder, never()).findNeighbours(peer_222, TARGET);
    verify(neighborFinder, never()).findNeighbours(peer_223, TARGET);

    verify(neighborFinder, never()).findNeighbours(peer_320, TARGET);
    verify(neighborFinder, never()).findNeighbours(peer_321, TARGET);
    verify(neighborFinder, never()).findNeighbours(peer_322, TARGET);

    verifyNoMoreInteractions(bondingAgent, neighborFinder);
  }

  @Test
  public void shouldNotQueryNodeThatIsAlreadyQueried() {}

  @Test
  public void shouldBondWithNewNeighboursWhenSomeRequestsTimeOut() {
    setupRelativePeerTree();

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

  private void setupRelativePeerTree() {

    try {
      JsonNode peers =
          MAPPER.readTree(RecursivePeerRefreshStateTest.class.getResource("/peers.json"));

      peer_000 = (TestPeer) generatePeer(peers);

      peer_010 = (TestPeer) peer_000.getPeerTable().get(0);

      peer_020 = (TestPeer) peer_010.getPeerTable().get(0);
      peer_021 = (TestPeer) peer_010.getPeerTable().get(1);
      peer_022 = (TestPeer) peer_010.getPeerTable().get(2);
      peer_023 = (TestPeer) peer_010.getPeerTable().get(3);

      peer_011 = (TestPeer) peer_000.getPeerTable().get(1);

      peer_120 = (TestPeer) peer_011.getPeerTable().get(0);
      peer_121 = (TestPeer) peer_011.getPeerTable().get(1);
      peer_122 = (TestPeer) peer_011.getPeerTable().get(2);
      peer_123 = (TestPeer) peer_011.getPeerTable().get(3);

      peer_012 = (TestPeer) peer_000.getPeerTable().get(2);

      peer_220 = (TestPeer) peer_012.getPeerTable().get(0);
      peer_221 = (TestPeer) peer_012.getPeerTable().get(1);
      peer_222 = (TestPeer) peer_012.getPeerTable().get(2);
      peer_223 = (TestPeer) peer_012.getPeerTable().get(3);

      peer_013 = (TestPeer) peer_000.getPeerTable().get(3);

      peer_320 = (TestPeer) peer_013.getPeerTable().get(0);
      peer_321 = (TestPeer) peer_013.getPeerTable().get(1);
      peer_322 = (TestPeer) peer_013.getPeerTable().get(2);
      peer_323 = (TestPeer) peer_013.getPeerTable().get(3);

      neighborsPacketData_000 = NeighborsPacketData.create(peer_000.getPeerTable());
      neighborsPacketData_010 = NeighborsPacketData.create(peer_010.getPeerTable());
      neighborsPacketData_011 = NeighborsPacketData.create(peer_011.getPeerTable());
      neighborsPacketData_012 = NeighborsPacketData.create(peer_012.getPeerTable());
      neighborsPacketData_013 = NeighborsPacketData.create(peer_013.getPeerTable());

      addPeersToAggregateListByOrdinalRank();
    } catch (Exception ignored) {
    }
  }

  private void addPeersToAggregateListByOrdinalRank() {
    aggregatePeerList.add(peer_323); // 1
    aggregatePeerList.add(peer_011); // 2
    aggregatePeerList.add(peer_012); // 3
    aggregatePeerList.add(peer_013); // 4
    aggregatePeerList.add(peer_020); // 5
    aggregatePeerList.add(peer_021); // 6
    aggregatePeerList.add(peer_022); // 7
    aggregatePeerList.add(peer_023); // 8
    aggregatePeerList.add(peer_120); // 9
    aggregatePeerList.add(peer_121); // 10
    aggregatePeerList.add(peer_122); // 11
    aggregatePeerList.add(peer_123); // 12
    aggregatePeerList.add(peer_220); // 13
    aggregatePeerList.add(peer_221); // 14
    aggregatePeerList.add(peer_222); // 15
    aggregatePeerList.add(peer_223); // 16
    aggregatePeerList.add(peer_320); // 17
    aggregatePeerList.add(peer_321); // 18
    aggregatePeerList.add(peer_322); // 19
    aggregatePeerList.add(peer_010); // 20
    aggregatePeerList.add(peer_000); // 21
  }

  @Test
  public void shouldConfirmPeersMatchCorrespondingPackets() {
    setupRelativePeerTree();

    assertThat(matchPeerToCorrespondingPacketData(peer_000, neighborsPacketData_000)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_010, neighborsPacketData_010)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_011, neighborsPacketData_011)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_012, neighborsPacketData_012)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_013, neighborsPacketData_013)).isTrue();
  }

  private DiscoveryPeer generatePeer(final JsonNode peer) {
    final int parent = peer.get("parent").asInt();
    final int tier = peer.get("tier").asInt();
    final int identifier = peer.get("identifier").asInt();
    final int ordinalRank = peer.get("ordinalRank").asInt();
    final BytesValue id = BytesValue.fromHexString(peer.get("id").asText());
    List<DiscoveryPeer> peerTable = new ArrayList<>();
    if (peer.get("peerTable") != null) {
      JsonNode peers = peer.get("peerTable");
      for (JsonNode element : peers) {
        peerTable.add(generatePeer(element));
      }
    } else {
      peerTable = Collections.emptyList();
    }
    return new TestPeer(parent, tier, identifier, ordinalRank, id, peerTable);
  }

  private boolean matchPeerToCorrespondingPacketData(
      final TestPeer peer, final NeighborsPacketData neighborsPacketData) {
    for (TestPeer neighbour :
        neighborsPacketData.getNodes().stream().map(p -> (TestPeer) p).collect(toList())) {
      if (neighbour.getParent() != peer.getIdentifier()) {
        return false;
      }
      if (neighbour.getTier() != peer.getTier() + 1) {
        return false;
      }
    }
    return true;
  }

  static class TestPeer extends DiscoveryPeer {
    int parent;
    int tier;
    int identifier;
    int ordinalRank;
    List<DiscoveryPeer> peerTable;

    TestPeer(
        final int parent,
        final int tier,
        final int identifier,
        final int ordinalRank,
        final BytesValue id,
        final List<DiscoveryPeer> peerTable) {
      super(id, "0.0.0.0", 1, 1);
      this.parent = parent;
      this.tier = tier;
      this.identifier = identifier;
      this.ordinalRank = ordinalRank;
      this.peerTable = peerTable;
    }

    int getParent() {
      return parent;
    }

    int getTier() {
      return tier;
    }

    int getIdentifier() {
      return identifier;
    }

    List<DiscoveryPeer> getPeerTable() {
      return peerTable;
    }

    @Override
    public Bytes32 keccak256() {
      return null;
    }

    @Override
    public Endpoint getEndpoint() {
      return null;
    }

    @Override
    public String toString() {
      return parent + "." + tier + "." + identifier;
    }
  }
}
