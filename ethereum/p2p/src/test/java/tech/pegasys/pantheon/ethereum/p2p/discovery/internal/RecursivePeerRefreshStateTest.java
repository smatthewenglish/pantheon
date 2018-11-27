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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import tech.pegasys.pantheon.ethereum.p2p.peers.Endpoint;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

public class RecursivePeerRefreshStateTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RecursivePeerRefreshState recursivePeerRefreshState;

  private final RecursivePeerRefreshState.BondingAgent bondingAgent =
      mock(RecursivePeerRefreshState.BondingAgent.class);
  private final RecursivePeerRefreshState.NeighborFinder neighborFinder =
      mock(RecursivePeerRefreshState.NeighborFinder.class);

  private final List<TestPeer> aggregatePeerList = new ArrayList<>();

  private NeighborsPacketData neighborsPacketData_bootstrap;
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

  private BytesValue target;

  @Before
  public void setup() throws Exception {
    target =
        BytesValue.fromHexString(
            "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");
    recursivePeerRefreshState =
        new RecursivePeerRefreshState(target, new PeerBlacklist(), bondingAgent, neighborFinder);

    JsonNode peers =
        MAPPER.readTree(RecursivePeerRefreshStateTest.class.getResource("/peers.json"));

    peer_000 = generatePeer(peers);

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

    neighborsPacketData_bootstrap = NeighborsPacketData.create(Collections.singletonList(peer_000));
    neighborsPacketData_000 = NeighborsPacketData.create(peer_000.getPeerTable());
    neighborsPacketData_010 = NeighborsPacketData.create(peer_010.getPeerTable());
    neighborsPacketData_011 = NeighborsPacketData.create(peer_011.getPeerTable());
    neighborsPacketData_012 = NeighborsPacketData.create(peer_012.getPeerTable());
    neighborsPacketData_013 = NeighborsPacketData.create(peer_013.getPeerTable());

    addPeersToAggregateListByOrdinalRank();
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

  private boolean matchPeerToCorrespondingPacketData(
      final Peer peer, final NeighborsPacketData neighborsPacketData) {
    String nodeId = null;
    Matcher idMatcher = Pattern.compile("\\d\\.\\d\\.(\\d)").matcher(peer.toString());
    if (idMatcher.find()) {
      nodeId = idMatcher.group(1);
    }
    for (Peer neighbour : neighborsPacketData.getNodes()) {
      idMatcher = Pattern.compile("(\\d)\\.(\\d)\\.(\\d)").matcher(neighbour.toString());
      if (idMatcher.find()) {
        String parentId = idMatcher.group(1);
        if (!nodeId.equals(parentId)) {
          return false;
        }
      }
    }
    return true;
  }

  @Test
  public void shouldConfirmAllPeersMatchCorrespondingPackets() {
    assertThat(matchPeerToCorrespondingPacketData(peer_000, neighborsPacketData_bootstrap))
        .isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_000, neighborsPacketData_000)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_010, neighborsPacketData_010)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_011, neighborsPacketData_011)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_012, neighborsPacketData_012)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_013, neighborsPacketData_013)).isTrue();
  }

  @Test
  public void shouldEstablishRelativeDistances() {
    for (int i = 0; i < aggregatePeerList.size() - 1; i++) {
      int nodeOrdinalRank = aggregatePeerList.get(i).getOrdinalRank();
      int neighborOrdinalRank = aggregatePeerList.get(i + 1).getOrdinalRank();
      assertThat(nodeOrdinalRank).isLessThan(neighborOrdinalRank);
    }
  }

  @Test
  public void shouldIssueRequestToPeerWithLesserDistanceButGreaterHops() {
    // Prerequisite for processing packets received by this peer...
    recursivePeerRefreshState.addToOutstandingRequests(peer_000.getId());
    // Take our bootstrap peer, and add it's peerTable to the anteMap
    // TODO:^^ this is inelegant, remove it...
    recursivePeerRefreshState.digestNeighboursPacket(
        neighborsPacketData_bootstrap, peer_000.getId());
    verify(bondingAgent).performBonding(peer_000);
    verify(neighborFinder).issueFindNodeRequest(peer_000);
    recursivePeerRefreshState.digestNeighboursPacket(neighborsPacketData_000, peer_000.getId());
    verify(bondingAgent).performBonding(peer_010);
    verify(bondingAgent).performBonding(peer_011);
    verify(bondingAgent).performBonding(peer_012);
    verify(bondingAgent).performBonding(peer_013);
    verify(neighborFinder, never()).issueFindNodeRequest(peer_010);
    verify(neighborFinder).issueFindNodeRequest(peer_011);
    verify(neighborFinder).issueFindNodeRequest(peer_012);
    verify(neighborFinder).issueFindNodeRequest(peer_013);
    recursivePeerRefreshState.digestNeighboursPacket(neighborsPacketData_011, peer_011.getId());
    verify(bondingAgent).performBonding(peer_120);
    verify(bondingAgent).performBonding(peer_121);
    verify(bondingAgent).performBonding(peer_122);
    verify(bondingAgent).performBonding(peer_123);
    recursivePeerRefreshState.digestNeighboursPacket(neighborsPacketData_012, peer_012.getId());
    verify(bondingAgent).performBonding(peer_220);
    verify(bondingAgent).performBonding(peer_221);
    verify(bondingAgent).performBonding(peer_222);
    verify(bondingAgent).performBonding(peer_223);
    recursivePeerRefreshState.digestNeighboursPacket(neighborsPacketData_013, peer_013.getId());
    verify(bondingAgent).performBonding(peer_320);
    verify(bondingAgent).performBonding(peer_321);
    verify(bondingAgent).performBonding(peer_322);
    verify(bondingAgent).performBonding(peer_323);
    verify(neighborFinder, never()).issueFindNodeRequest(peer_320);
    verify(neighborFinder, never()).issueFindNodeRequest(peer_321);
    verify(neighborFinder, never()).issueFindNodeRequest(peer_322);
    verify(neighborFinder).issueFindNodeRequest(peer_323);
  }

  private TestPeer generatePeer(final JsonNode peer) {
    int parent = peer.get("parent").asInt();
    int tier = peer.get("tier").asInt();
    int identifier = peer.get("identifier").asInt();
    int ordinalRank = peer.get("ordinalRank").asInt();
    BytesValue id = BytesValue.fromHexString(peer.get("id").asText());
    List<Peer> peerTable = new ArrayList<>();
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

  static class TestPeer implements Peer {
    int parent;
    int tier;
    int identifier;
    int ordinalRank;
    BytesValue id;
    List<Peer> peerTable;

    TestPeer(
        final int parent,
        final int tier,
        final int identifier,
        final int ordinalRank,
        final BytesValue id,
        final List<Peer> peerTable) {
      this.parent = parent;
      this.tier = tier;
      this.identifier = identifier;
      this.ordinalRank = ordinalRank;
      this.id = id;
      this.peerTable = peerTable;
    }

    int getOrdinalRank() {
      return ordinalRank;
    }

    public List<Peer> getPeerTable() {
      return peerTable;
    }

    @Override
    public BytesValue getId() {
      return this.id;
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
