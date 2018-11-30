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
import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDistanceCalculator.distance;

import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

class RecursivePeerRefreshState {
  private final int CONCURRENT_REQUEST_LIMIT = 3;
  private final BytesValue target;
  private final PeerBlacklist peerBlacklist;
  private final BondingAgent bondingAgent;
  private final NeighborFinder neighborFinder;
  private final List<PeerDistance> anteList;
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
    this.anteList = new ArrayList<>();
    this.outstandingRequestList = new ArrayList<>();
    this.contactedInCurrentExecution = new ArrayList<>();
  }

  void kickstartBootstrapPeers(final List<Peer> bootstrapPeers) {
    for (Peer bootstrapPeer : bootstrapPeers) {
      BytesValue peerId = bootstrapPeer.getId();
      outstandingRequestList.add(new OutstandingRequest(bootstrapPeer));
      contactedInCurrentExecution.add(peerId);
      bondingAgent.performBonding(bootstrapPeer);
      neighborFinder.issueFindNodeRequest(bootstrapPeer);
    }
  }

  void executeTimeoutEvaluation() { // This is the first thing that you'll call...
    List<OutstandingRequest> outstandingRequestListCopy = new ArrayList<>(outstandingRequestList);
    // For each peer in the list...
    for (OutstandingRequest outstandingRequest : outstandingRequestListCopy) {
      // If it *has* been previously evaluated, i.e. you've seen it before...
      if (outstandingRequest.getEvaluation()) {
        // Get all the queryable nodes, sorted by distance...
        List<Peer> queryCandidates = determineFindNodeCandidates(anteList.size());
        // For each of them in turn...
        for (Peer candidate : queryCandidates) {
          // If it hasn't already been contacted...
          if (!contactedInCurrentExecution.contains(candidate.getId())
              && !outstandingRequestList.contains(new OutstandingRequest(candidate))) {
            // And it isn't already one of our current requests...
            executeFindNodeRequest(candidate);
            // ^^^Then send it a FindNode request and start tracking it...
          }
        }
        outstandingRequestList.remove(outstandingRequest);
      }
      // Since we encountered it, mark it as eligible for eviction the next time this method is
      // called...
      outstandingRequest.setEvaluation();
    }
  }

  private void executeFindNodeRequest(final Peer peer) {
    BytesValue peerId = peer.getId();
    outstandingRequestList.add(new OutstandingRequest(peer));
    contactedInCurrentExecution.add(peerId);
    neighborFinder.issueFindNodeRequest(peer);
  }

  /**
   * The lookup initiator starts by picking CONCURRENT_REQUEST_LIMIT closest nodes to the target it
   * knows of. The initiator then issues concurrent FindNode packets to those nodes.
   */
  private void initiatePeerRefreshCycle(final List<Peer> peers) {
    for (Peer peer : peers) {
      if (!contactedInCurrentExecution.contains(peer.getId())) {
        executeFindNodeRequest(peer);
      }
    }
  }

  void digestNeighboursPacket(final NeighborsPacketData neighboursPacket, final Peer peer) {
    if (outstandingRequestList.contains(new OutstandingRequest(peer))) {
      List<DiscoveryPeer> receivedPeerList = neighboursPacket.getNodes();
      for (DiscoveryPeer receivedPeer : receivedPeerList) {
        if (!peerBlacklist.contains(receivedPeer)) {
          bondingAgent.performBonding(receivedPeer);
          anteList.add(new PeerDistance(receivedPeer, distance(target, receivedPeer.getId())));
        }
      }
      outstandingRequestList.remove(new OutstandingRequest(peer));
      performIteration();
    }
  }

  private List<Peer> determineFindNodeCandidates(final int threshold) {
    anteList.sort(
        (peer1, peer2) -> {
          if (peer1.getDistance() > peer2.getDistance()) return 1;
          if (peer1.getDistance() < peer2.getDistance()) return -1;
          return 0;
        });
    return anteList.subList(0, threshold).stream().map(PeerDistance::getPeer).collect(toList());
  }

  private void performIteration() {
    if (outstandingRequestList.isEmpty()) {
      List<Peer> queryCandidates = determineFindNodeCandidates(CONCURRENT_REQUEST_LIMIT);
      initiatePeerRefreshCycle(queryCandidates);
    }
  }

  static class PeerDistance {
    Peer peer;
    Integer distance;

    PeerDistance(final Peer peer, final Integer distance) {
      this.peer = peer;
      this.distance = distance;
    }

    Peer getPeer() {
      return peer;
    }

    Integer getDistance() {
      return distance;
    }

    @Override
    public String toString() {
      return peer + ": " + distance;
    }
  }

  static class OutstandingRequest {
    boolean evaluation;
    Peer peer;

    OutstandingRequest(final Peer peer) {
      this.evaluation = false;
      this.peer = peer;
    }

    boolean getEvaluation() {
      return evaluation;
    }

    Peer getPeer() {
      return peer;
    }

    void setEvaluation() {
      this.evaluation = true;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      OutstandingRequest that = (OutstandingRequest) o;
      return Objects.equals(peer.getId(), that.peer.getId());
    }

    @Override
    public int hashCode() {
      return Objects.hash(peer.getId());
    }

    @Override
    public String toString() {
      return peer.toString();
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
     * If peer is not previously known, initiate bonding process.
     *
     * @param peer
     */
    void performBonding(final Peer peer);
  }
}
