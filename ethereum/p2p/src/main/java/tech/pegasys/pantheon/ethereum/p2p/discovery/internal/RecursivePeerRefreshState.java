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

import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;
import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDistanceCalculator.distance;

class RecursivePeerRefreshState {
  private final int CONCURRENT_REQUEST_LIMIT = 3;
  private final BytesValue target;
  private final PeerBlacklist peerBlacklist;
  private final BondingAgent bondingAgent;
  private final NeighborFinder neighborFinder;

  private final List<PeerDistance> anteMap;
  private final Map<BytesValue, Instant> outstandingRequestList; //Want to check if a peer ID is in this list

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
    this.anteMap = new ArrayList<>();
    this.outstandingRequestList = new HashMap<>();
    this.contactedInCurrentExecution = new ArrayList<>();
  }

  /**
   * The lookup initiator starts by picking CONCURRENT_REQUEST_LIMIT closest nodes to the target it
   * knows of. The initiator then issues concurrent FindNode packets to those nodes.
   */
  private void initiatePeerRefreshCycle(final List<Peer> peers) {
    for (Peer peer : peers) {
      if (!contactedInCurrentExecution.contains(peer.getId())) {
        BytesValue peerId = peer.getId();
        outstandingRequestList.put(peer.getId(), Instant.now());
        contactedInCurrentExecution.add(peerId);
        neighborFinder.issueFindNodeRequest(peer);
      }
      // The lookup terminates when the initiator has queried
      // and gotten responses from the k closest nodes it has seen.
    }
  }

  void digestNeighboursPacket(
      final NeighborsPacketData neighboursPacket, final BytesValue peerIdentifier) {
    if (outstandingRequestList.containsKey(peerIdentifier)) {
      List<Peer> receivedPeerList = neighboursPacket.getNodes();
      for (Peer receivedPeer : receivedPeerList) {
        if (!peerBlacklist.contains(receivedPeer)) {
          bondingAgent.performBonding(receivedPeer);
          anteMap.add(new PeerDistance(receivedPeer, distance(target, receivedPeer.getId())));
        }
      }
      outstandingRequestList.remove(peerIdentifier);
      performIteration();
    }
  }

  private List<Peer> determineFindNodeCandidates() {
    anteMap.sort((peer1, peer2) -> {
      if (peer1.getDistance() > peer2.getDistance()) return 1;
      if (peer1.getDistance() < peer2.getDistance()) return -1;
      return 0;
    });
    return anteMap.subList(0, CONCURRENT_REQUEST_LIMIT).stream().map(PeerDistance::getPeer).collect(toList());
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
      outstandingRequestList.put(peerId, Instant.now());
      contactedInCurrentExecution.add(peerId);
      bondingAgent.performBonding(bootstrapPeer);
      neighborFinder.issueFindNodeRequest(bootstrapPeer);
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
