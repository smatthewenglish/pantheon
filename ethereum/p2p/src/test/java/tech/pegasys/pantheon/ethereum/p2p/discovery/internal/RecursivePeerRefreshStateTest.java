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

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.atIndex;
import static org.assertj.core.data.MapEntry.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDistanceCalculator.distance;

import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.Endpoint;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.ethereum.permissioning.PermissioningConfiguration;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;

public class RecursivePeerRefreshStateTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RecursivePeerRefreshState recursivePeerRefreshState;

  private final BytesValue target =
      BytesValue.fromHexString(
          "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");

  private final RecursivePeerRefreshState.PingDispatcher bondingAgent =
      mock(RecursivePeerRefreshState.PingDispatcher.class);
  private final RecursivePeerRefreshState.FindNeighbourDispatcher neighborFinder =
      mock(RecursivePeerRefreshState.FindNeighbourDispatcher.class);

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

  @Before
  public void setup() throws Exception {
    final JsonNode peers =
        MAPPER.readTree(RecursivePeerRefreshStateTest.class.getResource("/peers.json"));
    recursivePeerRefreshState =
        new RecursivePeerRefreshState(
            target,
            new PeerBlacklist(),
            new NodeWhitelistController(PermissioningConfiguration.createDefault()),
            bondingAgent,
            neighborFinder,
            30);

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
  public void shouldEstablishRelativeDistanceValues() {
    for (int i = 0; i < aggregatePeerList.size() - 1; i++) {
      final int nodeOrdinalRank = aggregatePeerList.get(i).getOrdinalRank();
      final int neighborOrdinalRank = aggregatePeerList.get(i + 1).getOrdinalRank();
      assertThat(nodeOrdinalRank).isLessThan(neighborOrdinalRank);
    }
  }

  @Test
  public void shouldConfirmPeersMatchCorrespondingPackets() {
    assertThat(matchPeerToCorrespondingPacketData(peer_000, neighborsPacketData_000)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_010, neighborsPacketData_010)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_011, neighborsPacketData_011)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_012, neighborsPacketData_012)).isTrue();
    assertThat(matchPeerToCorrespondingPacketData(peer_013, neighborsPacketData_013)).isTrue();
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

  @Test
  public void shouldIssueRequestToPeerWithLesserDistanceGreaterHops() {
    recursivePeerRefreshState.kickstartBootstrapPeers(Collections.singletonList(peer_000));
    recursivePeerRefreshState.start();

    verify(bondingAgent).ping(peer_000);
    verify(neighborFinder, never()).findNeighbours(peer_000, target);

    recursivePeerRefreshState.onPongPacketReceived(peer_000);

    verify(neighborFinder).findNeighbours(peer_000, target);

    recursivePeerRefreshState.onNeighboursPacketReceived(peer_000, neighborsPacketData_000);

    verify(bondingAgent).ping(peer_010);
    verify(bondingAgent).ping(peer_011);
    verify(bondingAgent).ping(peer_012);
    verify(bondingAgent).ping(peer_013);

    recursivePeerRefreshState.onPongPacketReceived(peer_010);
    recursivePeerRefreshState.onPongPacketReceived(peer_011);
    recursivePeerRefreshState.onPongPacketReceived(peer_012);
    recursivePeerRefreshState.onPongPacketReceived(peer_013);

    verify(neighborFinder, never()).findNeighbours(peer_010, target);
    verify(neighborFinder).findNeighbours(peer_011, target);
    verify(neighborFinder).findNeighbours(peer_012, target);
    verify(neighborFinder).findNeighbours(peer_013, target);

    recursivePeerRefreshState.onNeighboursPacketReceived(peer_011, neighborsPacketData_011);
    recursivePeerRefreshState.onNeighboursPacketReceived(peer_012, neighborsPacketData_012);
    recursivePeerRefreshState.onNeighboursPacketReceived(peer_013, neighborsPacketData_013);

    verify(bondingAgent).ping(peer_120);
    verify(bondingAgent).ping(peer_121);
    verify(bondingAgent).ping(peer_122);
    verify(bondingAgent).ping(peer_123);

    verify(bondingAgent).ping(peer_220);
    verify(bondingAgent).ping(peer_221);
    verify(bondingAgent).ping(peer_222);
    verify(bondingAgent).ping(peer_223);

    verify(bondingAgent).ping(peer_320);
    verify(bondingAgent).ping(peer_321);
    verify(bondingAgent).ping(peer_322);
    verify(bondingAgent).ping(peer_323);

    recursivePeerRefreshState.onPongPacketReceived(peer_120);
    recursivePeerRefreshState.onPongPacketReceived(peer_121);
    recursivePeerRefreshState.onPongPacketReceived(peer_122);
    recursivePeerRefreshState.onPongPacketReceived(peer_123);

    recursivePeerRefreshState.onPongPacketReceived(peer_220);
    recursivePeerRefreshState.onPongPacketReceived(peer_221);
    recursivePeerRefreshState.onPongPacketReceived(peer_222);
    recursivePeerRefreshState.onPongPacketReceived(peer_223);

    recursivePeerRefreshState.onPongPacketReceived(peer_320);
    recursivePeerRefreshState.onPongPacketReceived(peer_321);
    recursivePeerRefreshState.onPongPacketReceived(peer_322);
    recursivePeerRefreshState.onPongPacketReceived(peer_323);

    verify(neighborFinder, never()).findNeighbours(peer_320, target);
    verify(neighborFinder, never()).findNeighbours(peer_321, target);
    verify(neighborFinder, never()).findNeighbours(peer_322, target);
    verify(neighborFinder).findNeighbours(peer_323, target);
  }

  @Test
  public void shouldIssueRequestToPeerWithGreaterDistanceOnExpirationOfLowerDistancePeerRequest() {
    //    recursivePeerRefreshState.kickstartBootstrapPeers(Collections.singletonList(peer_000));
    //    recursivePeerRefreshState.neighboursTimeoutEvaluation();
    //
    //    verify(neighborFinder, never()).findNeighbours(peer_000, target);
    //    verify(bondingAgent).ping(peer_000);
    //
    //    recursivePeerRefreshState.onPongPacketReceived(peer_000);
    //
    //    recursivePeerRefreshState.onNeighboursPacketReceived(neighborsPacketData_000, peer_000);
    //
    //    recursivePeerRefreshState.onPongPacketReceived(peer_010);
    //    recursivePeerRefreshState.onPongPacketReceived(peer_011);
    //    recursivePeerRefreshState.onPongPacketReceived(peer_012);
    //    recursivePeerRefreshState.onPongPacketReceived(peer_013);
    //
    //    recursivePeerRefreshState.neighboursTimeoutEvaluation();
    //
    //    verify(neighborFinder, never()).findNeighbours(peer_010, target);
    //    verify(neighborFinder).findNeighbours(peer_011, target);
    //    verify(neighborFinder).findNeighbours(peer_012, target);
    //    verify(neighborFinder).findNeighbours(peer_013, target);
    //
    //    recursivePeerRefreshState.neighboursTimeoutEvaluation();
    //
    //    verify(neighborFinder).findNeighbours(peer_010, target);
  }

  private DiscoveryPeer generatePeer(final JsonNode peer) {
    final int parent = peer.get("parent").asInt();
    final int tier = peer.get("tier").asInt();
    final int identifier = peer.get("identifier").asInt();
    final int ordinalRank = peer.get("ordinalRank").asInt();
    BytesValue id = BytesValue.fromHexString(peer.get("id").asText());
    List<DiscoveryPeer> peerTable = new ArrayList<>();
    if (peer.get("peerTable") != null) {
      final JsonNode peers = peer.get("peerTable");
      for (JsonNode element : peers) {
        peerTable.add(generatePeer(element));
      }
    } else {
      peerTable = Collections.emptyList();
    }
    return new TestPeer(parent, tier, identifier, ordinalRank, id, peerTable);
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

    int getOrdinalRank() {
      return ordinalRank;
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

  public static class MetadataPeer implements Comparable<MetadataPeer> {
    DiscoveryPeer peer;
    Integer distance;

    boolean bondEvaluated;
    boolean bondQueried;
    boolean bondResponded;

    boolean neighbourEvaluated;
    boolean neighbourQueried;
    boolean neighbourResponded;

    @Override
    public int compareTo(final MetadataPeer o) {
      if (this.distance > o.distance) return 1;
      return -1;
    }

    public MetadataPeer(final DiscoveryPeer peer, final Integer distance) {
      this.peer = peer;
      this.distance = distance;

      this.bondEvaluated = false;
      this.bondQueried = false;
      this.bondResponded = false;

      this.neighbourEvaluated = false;
      this.neighbourQueried = false;
      this.neighbourResponded = false;
    }

    DiscoveryPeer getPeer() {
      return peer;
    }

    public Integer getDistance() {
      return distance;
    }

    void setBondQueried() {
      this.bondQueried = true;
    }

    boolean getBondQueried() {
      return bondQueried;
    }

    void setBondResponded() {
      this.bondResponded = true;
    }

    boolean getBondResponded() {
      return bondResponded;
    }

    void setBondEvaluation() {
      this.bondEvaluated = true;
    }

    boolean getBondEvaluation() {
      return bondEvaluated;
    }

    void setNeighbourQueried() {
      this.neighbourQueried = true;
    }

    boolean getNeighbourQueried() {
      return neighbourQueried;
    }

    void setNeighbourResponded() {
      this.neighbourResponded = true;
    }

    boolean getNeighbourResponded() {
      return neighbourResponded;
    }

    void setNeighbourEvaluation() {
      this.neighbourEvaluated = true;
    }

    boolean getNeighbourEvaluation() {
      return neighbourEvaluated;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      final MetadataPeer that = (MetadataPeer) o;
      return Objects.equals(peer.getId(), that.peer.getId());
    }

    @Override
    public int hashCode() {
      return Objects.hash(peer.getId());
    }

    @Override
    public String toString() {
      return peer + ": " + distance;
    }
  }

  @Test
  public void testSortMetadataBonding() {

    final MetadataPeer peerA = new MetadataPeer(peer_020, distance(target, peer_020.getId()));

    final MetadataPeer peerB = new MetadataPeer(peer_021, distance(target, peer_021.getId()));
    peerB.setBondQueried();

    final MetadataPeer peerC = new MetadataPeer(peer_022, distance(target, peer_022.getId()));

    final MetadataPeer peerD =
        new MetadataPeer(
            peer_023, distance(target, peer_023.getId())); // Not returned on threshold 3

    final MetadataPeer peerE = new MetadataPeer(peer_120, distance(target, peer_120.getId()));

    final SortedMap<BytesValue, MetadataPeer> oneTrueMap = new TreeMap<>();

    oneTrueMap.put(peer_023.getId(), peerD);
    oneTrueMap.put(peer_022.getId(), peerC);
    oneTrueMap.put(peer_021.getId(), peerB);
    oneTrueMap.put(peer_020.getId(), peerA);
    oneTrueMap.put(peer_120.getId(), peerE);

    try {
      assertThat(oneTrueMap)
          .containsExactly(
              entry(peer_020.getId(), peerA),
              entry(peer_021.getId(), peerB),
              entry(peer_022.getId(), peerC),
              entry(peer_023.getId(), peerD),
              entry(peer_120.getId(), peerE));
    } catch (Exception e) {
      System.err.println("Contains disorder.");
    }

    final List<DiscoveryPeer> bondingRoundCandidatesList =
        bondingRoundCandidates(oneTrueMap.size(), oneTrueMap);

    assertThat(bondingRoundCandidatesList).contains(peerA.getPeer(), atIndex(0));
    assertThat(bondingRoundCandidatesList).contains(peerC.getPeer(), atIndex(1));
    assertThat(bondingRoundCandidatesList).contains(peerD.getPeer(), atIndex(2));
    assertThat(bondingRoundCandidatesList).contains(peerE.getPeer(), atIndex(3));
  }

  private List<DiscoveryPeer> bondingRoundCandidates(
      final int max, final SortedMap<BytesValue, MetadataPeer> source) {
    final List<DiscoveryPeer> candidatesList = new ArrayList<>();

    int count = 0;
    for (Map.Entry<BytesValue, MetadataPeer> candidateEntry : source.entrySet()) {
      if (count >= max) {
        break;
      }
      final MetadataPeer candidate = candidateEntry.getValue();

      if (!candidate.getNeighbourEvaluation()
          && !candidate.getNeighbourResponded()
          && !candidate.getNeighbourQueried()
          &&
          //
          !candidate.getBondEvaluation()
          && !candidate.getBondResponded()
          && !candidate.getBondQueried()) {

        candidatesList.add(candidate.getPeer());
        count++;
      }
    }
    return candidatesList;
  }

  private List<DiscoveryPeer> neighboursRoundCandidates(
      final int max, final SortedMap<BytesValue, MetadataPeer> source) {
    final List<DiscoveryPeer> candidatesList = new ArrayList<>();

    int count = 0;
    for (Map.Entry<BytesValue, MetadataPeer> candidateEntry : source.entrySet()) {
      if (count >= max) {
        break;
      }
      final MetadataPeer candidate = candidateEntry.getValue();

      if (candidate.getBondQueried() && candidate.getBondResponded()) {
        candidatesList.add(candidate.getPeer());
        count++;
      }
    }
    return candidatesList;
  }

  @Test
  public void testSortMetadataNeighbours() {

    final MetadataPeer peerA = new MetadataPeer(peer_020, distance(target, peer_020.getId()));
    peerA.setBondQueried();
    peerA.setBondEvaluation(); // !!!

    final MetadataPeer peerB = new MetadataPeer(peer_021, distance(target, peer_021.getId()));
    peerB.setBondQueried();
    peerB.setBondResponded();

    final MetadataPeer peerC = new MetadataPeer(peer_022, distance(target, peer_022.getId()));
    peerC.setBondQueried();
    peerC.setBondResponded();

    final MetadataPeer peerD =
        new MetadataPeer(
            peer_023, distance(target, peer_023.getId())); // Not returned on threshold 3
    peerD.setBondQueried();
    peerD.setBondResponded();

    final SortedMap<BytesValue, MetadataPeer> oneTrueMap = new TreeMap<>();

    oneTrueMap.put(peer_023.getId(), peerD);
    oneTrueMap.put(peer_022.getId(), peerC);
    oneTrueMap.put(peer_021.getId(), peerB);
    oneTrueMap.put(peer_020.getId(), peerA);

    try {
      assertThat(oneTrueMap)
          .containsExactly(
              entry(peer_020.getId(), peerA),
              entry(peer_021.getId(), peerB),
              entry(peer_022.getId(), peerC),
              entry(peer_023.getId(), peerD));
    } catch (Exception e) {
      System.err.println("Contains disorder.");
    }

    final List<DiscoveryPeer> bondingRoundCandidatesList = neighboursRoundCandidates(3, oneTrueMap);

    assertThat(bondingRoundCandidatesList).contains(peerB.getPeer(), atIndex(0));
    assertThat(bondingRoundCandidatesList).contains(peerC.getPeer(), atIndex(1));
    assertThat(bondingRoundCandidatesList).contains(peerD.getPeer(), atIndex(2));
  }

  @Test
  public void completableFuture() throws Exception {

    final Future<String> completableFuture = calculateAsync();
    final String result = completableFuture.get();

    System.out.println(result);
    assertThat(result).isEqualTo("Hello");
  }

  private Future<String> calculateAsync() {
    final CompletableFuture<String> completableFuture = new CompletableFuture<>();

    Executors.newCachedThreadPool()
        .submit(
            () -> {
              Thread.sleep(500);
              completableFuture.complete("Hello");
              return null;
            });

    return completableFuture;
  }
}
