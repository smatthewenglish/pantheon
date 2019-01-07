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
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import com.google.common.annotations.VisibleForTesting;

class RecursivePeerRefreshState {

  private final BytesValue target;
  private final PeerBlacklist peerBlacklist;
  private final NodeWhitelistController peerWhitelist;

  private final BondingAgent bondingAgent;
  private final NeighborFinder neighbourFinder;

  private final List<PeerDistance> anteList;

  private final List<OutstandingRequest> outstandingBondingRequestList;
  private final List<OutstandingRequest> outstandingNeighboursRequestList;

  private final LinkedHashSet<BytesValue> dispatchedFindNeighbours;
  private final LinkedHashSet<BytesValue> dispatchedBond;

  RecursivePeerRefreshState(
      final BytesValue target,
      final PeerBlacklist peerBlacklist,
      final NodeWhitelistController peerWhitelist,
      final BondingAgent bondingAgent,
      final NeighborFinder neighborFinder) {
    this.target = target;
    this.peerBlacklist = peerBlacklist;
    this.peerWhitelist = peerWhitelist;
    this.bondingAgent = bondingAgent;
    this.neighbourFinder = neighborFinder;
    this.anteList = new ArrayList<>();

    this.outstandingBondingRequestList = new ArrayList<>();
    this.outstandingNeighboursRequestList = new ArrayList<>();

    this.dispatchedBond = new LinkedHashSet<>();
    this.dispatchedFindNeighbours = new LinkedHashSet<>();
  }

  void kickstartBootstrapPeers(final List<DiscoveryPeer> bootstrapPeers) {
    for (DiscoveryPeer bootstrapPeer : bootstrapPeers) {
      bondingAgent.performBonding(bootstrapPeer);
      dispatchedBond.add(bootstrapPeer.getId());
      outstandingBondingRequestList.add(new OutstandingRequest(bootstrapPeer));
    }
  }

  void onPongPacketReceived(final DiscoveryPeer peer) {
    outstandingBondingRequestList.remove(new OutstandingRequest(peer));
    anteList.add(new PeerDistance(peer, distance(target, peer.getId())));
    if (outstandingBondingRequestList.isEmpty()) {
      queryNearestNodes();
    }
  }

  /**
   * This method is intended to be called periodically by the {@link PeerDiscoveryController}, which
   * will maintain a timer for purposes of effecting expiration of requests outstanding. Requests
   * once encountered are deemed eligible for eviction if they have not been dispatched before the
   * next invocation of the method.
   */
  void neighboursTimeoutEvaluation() {
    for (int i = 0; i < outstandingNeighboursRequestList.size(); i++) {
      if (outstandingNeighboursRequestList.get(i).getEvaluation()) {
        final List<DiscoveryPeer> queryCandidates = determineFindNodeCandidates(anteList.size());
        for (DiscoveryPeer candidate : queryCandidates) {
          if (!dispatchedFindNeighbours.contains(candidate.getId())
              && !outstandingNeighboursRequestList.contains(new OutstandingRequest(candidate))) {
            outstandingNeighboursRequestList.remove(i);
            executeFindNodeRequest(candidate);
          }
        }
      }
      outstandingNeighboursRequestList.get(i).setEvaluation();
    }
  }

  /** TODO: Implement this... */
  void bondingTimeoutEvaluation() {}

  private void executeFindNodeRequest(final DiscoveryPeer peer) {
    outstandingNeighboursRequestList.add(new OutstandingRequest(peer));
    dispatchedFindNeighbours.add(peer.getId());
    neighbourFinder.issueFindNodeRequest(peer, target);
  }

  /**
   * The lookup initiator starts by picking CONCURRENT_REQUEST_LIMIT closest nodes to the target it
   * knows of. The initiator then issues concurrent FindNode packets to those nodes.
   */
  private void initiatePeerRefreshCycle(final List<DiscoveryPeer> peers) {
    for (DiscoveryPeer peer : peers) {
      if (!dispatchedFindNeighbours.contains(peer.getId())) {
        executeFindNodeRequest(peer);
      }
    }
    // The lookup terminates when the initiator has queried
    // and gotten responses from the k closest nodes it has seen.
  }

  void onNeighboursPacketReceived(
      final NeighborsPacketData neighboursPacket, final DiscoveryPeer peer) {
    if (outstandingNeighboursRequestList.contains(new OutstandingRequest(peer))) {
      final List<DiscoveryPeer> receivedPeerList = neighboursPacket.getNodes();
      for (DiscoveryPeer receivedPeer : receivedPeerList) {
        if (!dispatchedBond.contains(receivedPeer.getId())
            && !peerBlacklist.contains(receivedPeer)
            && peerWhitelist.contains(receivedPeer)) {
          bondingAgent.performBonding(receivedPeer);
          outstandingBondingRequestList.add(new OutstandingRequest(receivedPeer));
          dispatchedBond.add(receivedPeer.getId());
        }
      }
      outstandingNeighboursRequestList.remove(new OutstandingRequest(peer));
    }
  }

  private List<DiscoveryPeer> determineFindNodeCandidates(final int threshold) {
    anteList.sort(
        (peer1, peer2) -> {
          if (peer1.getDistance() > peer2.getDistance()) return 1;
          if (peer1.getDistance() < peer2.getDistance()) return -1;
          return 0;
        });
    return anteList
        .subList(0, Math.min(anteList.size(), threshold))
        .stream()
        .map(PeerDistance::getPeer)
        .collect(toList());
  }

  private void queryNearestNodes() {
    final int concurrentRequestLimit = 3;
    if (outstandingNeighboursRequestList.isEmpty()) {
      final List<DiscoveryPeer> queryCandidates =
          determineFindNodeCandidates(concurrentRequestLimit);
      initiatePeerRefreshCycle(queryCandidates);
    }
  }

  @VisibleForTesting
  List<OutstandingRequest> getOutstandingNeighboursRequestList() {
    return outstandingNeighboursRequestList;
  }

  static class PeerDistance {
    DiscoveryPeer peer;
    Integer distance;

    PeerDistance(final DiscoveryPeer peer, final Integer distance) {
      this.peer = peer;
      this.distance = distance;
    }

    DiscoveryPeer getPeer() {
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

  public static class OutstandingRequest {
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
      final OutstandingRequest that = (OutstandingRequest) o;
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
     * Sends a FIND_NEIGHBORS message to a {@link DiscoveryPeer}, in search of a target value.
     *
     * @param peer the peer to interrogate
     * @param target the target node ID to find
     */
    void issueFindNodeRequest(final DiscoveryPeer peer, final BytesValue target);
  }

  public interface BondingAgent {
    /**
     * Initiates a bonding PING-PONG cycle with a peer.
     *
     * @param peer The targeted peer.
     */
    void performBonding(final DiscoveryPeer peer);
  }
}
