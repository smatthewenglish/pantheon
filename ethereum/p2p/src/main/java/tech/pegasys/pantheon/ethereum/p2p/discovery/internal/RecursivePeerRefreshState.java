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

import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDistanceCalculator.distance;

import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

class RecursivePeerRefreshState {
  private final int CONCURRENT_REQUEST_LIMIT = 3;
  private final BytesValue target;
  private final PeerBlacklist peerBlacklist;
  private final BondingAgent bondingAgent;
  private final NeighborFinder neighborFinder;
  private final HashMap<Peer, Integer> anteMap;
  private final SortedSet<Map.Entry<Peer, Integer>> distanceSortedPeers;
  private final List<OutstandingRequest> outstandingRequestList;
  private final List<BytesValue> contactedInCurrentExecution;

  RecursivePeerRefreshState(
      final BytesValue target,
      final PeerBlacklist peerBlacklist,
      final BondingAgent bondingAgent,
      final NeighborFinder neighborFinder) {
    this.target = target;
    this.peerBlacklist = peerBlacklist;
    this.bondingAgent = bondingAgent;
    this.neighborFinder = neighborFinder;
    this.anteMap = new HashMap<>();
    this.distanceSortedPeers = new TreeSet<>(Comparator.comparing(Map.Entry::getValue));
    this.outstandingRequestList = new ArrayList<>();
    this.contactedInCurrentExecution = new ArrayList<>();
  }

  /**
   * The lookup initiator starts by picking CONCURRENT_REQUEST_LIMIT closest nodes to the target it
   * knows of. The initiator then sends concurrent FindNode packets to those nodes.
   */
  private void initiatePeerRefreshCycle(final List<Peer> peers) {
    for (Peer peer : peers) {
      if (!contactedInCurrentExecution.contains(peer.getId())) {
        BytesValue peerId = peer.getId();
        outstandingRequestList.add(new OutstandingRequest(peerId));
        contactedInCurrentExecution.add(peerId);
        neighborFinder.issueFindNodeRequest(peer);
      }
      // The lookup terminates when the initiator has queried
      // and gotten responses from the k closest nodes it has seen.
    }
  }

  void digestNeighboursPacket(
      final NeighborsPacketData neighboursPacket, final BytesValue peerIdentifier) {
    if (outstandingRequestList.contains(new OutstandingRequest(peerIdentifier))) {
      List<Peer> receivedPeerList = neighboursPacket.getNodes();
      for (Peer receivedPeer : receivedPeerList) {
        if (!peerBlacklist.contains(receivedPeer)) {
          bondingAgent.performBonding(receivedPeer);
          anteMap.put(receivedPeer, distance(target, receivedPeer.getId()));
        }
      }
      outstandingRequestList.remove(new OutstandingRequest(peerIdentifier));
      performIteration();
    }
  }

  private List<Peer> determineFindNodeCandidates() {
    distanceSortedPeers.addAll(anteMap.entrySet());
    List<Map.Entry<Peer, Integer>> sortedList = new ArrayList<>(distanceSortedPeers);
    List<Peer> queryCandidates = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUEST_LIMIT; i++) {
      if (i >= sortedList.size()) {
        break;
      }
      queryCandidates.add(sortedList.get(i).getKey());
    }
    return queryCandidates;
  }

  private void performIteration() {
    if (outstandingRequestList.isEmpty()) {
      List<Peer> queryCandidates = determineFindNodeCandidates();
      initiatePeerRefreshCycle(queryCandidates);
    }
  }

  void kickstartBootstrapPeers(final List<Peer> bootstrapPeers) {
    for (Peer bootstrapPeer : bootstrapPeers) {
      BytesValue peerId = bootstrapPeer.getId();
      outstandingRequestList.add(new OutstandingRequest(peerId));
      contactedInCurrentExecution.add(peerId);
      bondingAgent.performBonding(bootstrapPeer);
      neighborFinder.issueFindNodeRequest(bootstrapPeer);
    }
  }

  static class OutstandingRequest {
    Instant creation;
    BytesValue peerId;

    OutstandingRequest(final BytesValue peerId) {
      this.creation = Instant.now();
      this.peerId = peerId;
    }

    boolean isExpired() {
      Duration duration = Duration.between(creation, Instant.now());
      Duration limit = Duration.ofSeconds(30);
      return duration.compareTo(limit) >= 0;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      OutstandingRequest that = (OutstandingRequest) o;
      return peerId.equals(that.peerId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(peerId);
    }
  }

  public interface NeighborFinder {
    /**
     * Wait for the peer to complete bonding before issuance of FindNode request.
     *
     * @param peer
     */
    void issueFindNodeRequest(final Peer peer);
  }

  public interface BondingAgent {
    /**
     * If peer is not previously known initiate bonding process.
     *
     * @param peer
     */
    void performBonding(final Peer peer);
  }
}
