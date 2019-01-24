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

import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDistanceCalculator.distance;

class RecursivePeerRefreshState {

  private final PeerTable peerTable;

  private final SortedMap<BytesValue, MetadataPeer> oneTrueMap;

  private final BondingAgent bondingAgent;
  private final FindNeighbourDispatcher findNeighbourDispatcher;

  private final PeerBlacklist peerBlacklist;
  private final NodeWhitelistController peerWhitelist;

  private final ScheduledExecutorService neighboursScheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
  private final int timeoutPeriod;

  private BytesValue target;

  RecursivePeerRefreshState(
          final PeerTable peerTable,
          final PeerBlacklist peerBlacklist,
          final NodeWhitelistController peerWhitelist,
          final BondingAgent bondingAgent,
          final FindNeighbourDispatcher neighborFinder,
          final int timeoutPeriod) {
    this.peerTable = peerTable;
    this.peerBlacklist = peerBlacklist;
    this.peerWhitelist = peerWhitelist;
    this.bondingAgent = bondingAgent;
    this.findNeighbourDispatcher = neighborFinder;
    this.timeoutPeriod = timeoutPeriod;
    this.oneTrueMap = new TreeMap<>();
  }

  void start(final BytesValue target) {
    this.target = target;
    neighboursInitiateRound(peerTable.nearestPeers(target, 3));
  }

  private void neighboursInitiateRound(final List<DiscoveryPeer> neighboursRoundCandidatesList) {
    for (DiscoveryPeer discoPeer : neighboursRoundCandidatesList) {
      findNeighbourDispatcher.findNeighbours(discoPeer, target);
      final MetadataPeer metadataPeer = oneTrueMap.get(discoPeer.getId());
      metadataPeer.setNeighbourQueried();
    }
  }

  private void cancelOutstandingNeighboursRequests() {
    for (Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.getNeighbourQueried() && !metadataPeer.getNeighbourResponded()) {
        metadataPeer.setNeighbourCancelled();
      }
    }
    final List<DiscoveryPeer> neighboursRoundCandidatesList = neighboursRoundCandidates(3, oneTrueMap);
    if (neighboursRoundCandidatesList.size() > 0) {
      neighboursInitiateRound(neighboursRoundCandidatesList);
    }
  }

  void onNeighboursPacketReceived(final DiscoveryPeer peer, final NeighborsPacketData neighboursPacket) {
    final MetadataPeer metadataPeer = oneTrueMap.get(peer.getId());
    if (metadataPeer == null) { // TODO: Generate an error about unanticipated message...
      return;
    }
    final List<DiscoveryPeer> receivedPeerList = neighboursPacket.getNodes();
    for (DiscoveryPeer receivedDiscoveryPeer : receivedPeerList) {
      if (!oneTrueMap.containsKey(receivedDiscoveryPeer.getId())  // If we haven't seen it before in this refresh iteration...
              && !peerBlacklist.contains(receivedDiscoveryPeer)
              && peerWhitelist.contains(receivedDiscoveryPeer)) {

        final MetadataPeer receivedMetadataPeer = new MetadataPeer(receivedDiscoveryPeer, distance(target, receivedDiscoveryPeer.getId()));
        oneTrueMap.put(receivedDiscoveryPeer.getId(), receivedMetadataPeer);
      }
    }
    metadataPeer.setNeighbourResponded();

    if (neighboursRoundTermination()) {
      //final List<DiscoveryPeer> bondingRoundCandidatesList = bondingRoundCandidates(oneTrueMap.size(), oneTrueMap);
      //bondingInitiateRound(bondingRoundCandidatesList);
    }
  }

  private boolean neighboursRoundTermination() {
    for (Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.getNeighbourQueried() && !(metadataPeer.getNeighbourResponded() || metadataPeer.getNeighbourCancelled())) {
        return false;
      }
    }
    return true;
  }

  private List<DiscoveryPeer> neighboursRoundCandidates(final int max, final SortedMap<BytesValue, MetadataPeer> source) {
    final int threshold = Math.min(oneTrueMap.size(), max);
    final List<DiscoveryPeer> candidatesList = new ArrayList<>();

    int count = 0;
    for (Map.Entry<BytesValue, MetadataPeer> candidateEntry : source.entrySet()) {
      if (count >= threshold) {
        break;
      }
      final MetadataPeer candidate = candidateEntry.getValue();
      if (candidate.getBondQueried()
              && candidate.getBondResponded()
              && !candidate.getNeighbourCancelled()
              && !candidate.getNeighbourQueried()
              && !candidate.getNeighbourResponded()) {
        candidatesList.add(candidate.getPeer());
        count++;
      }
    }
    return candidatesList;
  }

  public static class MetadataPeer implements Comparable<MetadataPeer> {
    DiscoveryPeer peer;
    Integer distance;

    boolean neighbourQueried;
    boolean neighbourResponded;
    boolean neighbourCancelled;

    @Override
    public int compareTo(final MetadataPeer o) {
      if (this.distance > o.distance) {
        return 1;
      }
      return -1;
    }

    MetadataPeer(final DiscoveryPeer peer, final Integer distance) {
      this.peer = peer;
      this.distance = distance;

      this.neighbourQueried = false;
      this.neighbourResponded = false;
      this.neighbourCancelled = false;
    }

    DiscoveryPeer getPeer() {
      return peer;
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

    void setNeighbourCancelled() {
      this.neighbourCancelled = true;
    }

    boolean getNeighbourCancelled() {
      return neighbourCancelled;
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

  @FunctionalInterface
  public interface FindNeighbourDispatcher {
    /**
     * Sends a FIND_NEIGHBORS message to a {@link DiscoveryPeer}, in search of a target value.
     *
     * @param peer the peer to interrogate
     * @param target the target node ID to find
     */
    void findNeighbours(final DiscoveryPeer peer, final BytesValue target);
  }

  @FunctionalInterface
  public interface BondingAgent {
    /**
     * Initiates a bonding PING-PONG cycle with a peer.
     *
     * @param peer The targeted peer.
     * @param bootstrap Whether this is a bootstrap interaction.
     */
    void performBonding(final DiscoveryPeer peer, final boolean bootstrap);
  }
}