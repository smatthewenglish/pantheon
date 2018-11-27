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
  private SortedSet<Map.Entry<Peer, Integer>> distanceSortedPeers;
  List<OutstandingRequest> outstandingRequestList;
  // private List<BytesValue> outstandingRequestList;
  private List<BytesValue> contactedInCurrentExecution;

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

  BytesValue getTarget() {
    return target;
  }

  SortedSet<Map.Entry<Peer, Integer>> getDistanceSortedPeers() {
    return distanceSortedPeers;
  }

  HashMap<Peer, Integer> getAnteMap() {
    return anteMap;
  }

  List<OutstandingRequest> getOutstandingRequestList() {
    return outstandingRequestList;
  }

  void addToOutstandingRequests(final BytesValue id) {
    outstandingRequestList.add(new OutstandingRequest(id));
  }

  private void performIteration() {
    if (outstandingRequestList.isEmpty()) {
      // Determine the peers of whom we will request nodes...
      List<Peer> queryCandidates = determineFindNodeCandidates();
      initiatePeerRefreshCycle(queryCandidates);
    }
  }

  /**
   * This method is called once in the beginning with the bootstrap node(s), and then subsequently
   * in each iteration, i.e. 'performIteration()'.
   *
   * <p>The lookup initiator starts by picking α closest nodes to the target it knows of. The
   * initiator then sends concurrent FindNode packets to those nodes. α is a system-wide concurrency
   * parameter, such as 3.
   *
   * @param peers closest peers, no more than CONCURRENT_REQUEST_LIMIT
   */
  private void initiatePeerRefreshCycle(final List<Peer> peers) {
    for (Peer peer : peers) {
      // When all peers are constituents of contactedInCurrentExecution
      // the algorithm is over...
      if (!contactedInCurrentExecution.contains(peer.getId())) {
        // Save the name of this peer, so we know when it responds to us
        BytesValue peerId = peer.getId();
        outstandingRequestList.add(new OutstandingRequest(peerId));
        contactedInCurrentExecution.add(peerId);
        neighborFinder.issueFindNodeRequest(peer);
      }
    }
  }

  /** Process our peers and determine the query candidates for a new round... */
  private List<Peer> determineFindNodeCandidates() {
    // Add the newly updated 'anteMap' to the 'distanceSortedPeers' data structure...
    distanceSortedPeers.addAll(anteMap.entrySet());
    // Create a structure that we're able to index into, from the newly sorted set...
    List<Map.Entry<Peer, Integer>> sortedList = new ArrayList<>(distanceSortedPeers);
    // Create a structure to hold the result of our gathering our closest peers...
    List<Peer> queryCandidates = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUEST_LIMIT; i++) {
      if (i >= sortedList.size()) {
        break;
      }
      queryCandidates.add(sortedList.get(i).getKey());
    }
    return queryCandidates;
  }

  /** Process a received packet, add the contents, i.e. new peers, to the 'anteMap'. */
  void digestNeighboursPacket(
      final NeighborsPacketData neighboursPacket, final BytesValue peerIdentifier) {
    if (outstandingRequestList.contains(new OutstandingRequest(peerIdentifier))) {
      // Get the peers from this packet...
      List<Peer> receivedPeerList = neighboursPacket.getNodes();
      // Treat each one of them individually...
      for (Peer receivedPeer : receivedPeerList) {
        if (!peerBlacklist.contains(receivedPeer)) {
          // Add to our peerTable...
          bondingAgent.performBonding(receivedPeer);
          // Add each one to our 'anteMap'...
          anteMap.put(receivedPeer, distance(target, receivedPeer.getId()));
        }
      }
      // We are no longer waiting on this peer's return message
      outstandingRequestList.remove(new OutstandingRequest(peerIdentifier));
      performIteration();
    }
    // If we received a neighbours packet from a peer from whom
    // we've not requested data, something fishy is going on...
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
     * Wait for the peer to complete bonding then issue a find node request...
     *
     * @param peer
     */
    void issueFindNodeRequest(Peer peer);
  }

  public interface BondingAgent {
    /**
     * If peer is not already in the peer table or started the bonding process, begin bonding...
     *
     * @param peer
     */
    void performBonding(Peer peer);
  }
}
