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

import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
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
import java.util.concurrent.TimeUnit;

public class RecursivePeerRefreshState {
  private final BytesValue target;
  private final PeerBlacklist peerBlacklist;
  private final NodeWhitelistController peerWhitelist;

  private final PingDispatcher pingDispatcher;
  private final FindNeighbourDispatcher findNeighbourDispatcher;

  private final SortedMap<BytesValue, MetadataPeer> oneTrueMap;

  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newScheduledThreadPool(2);
  private final int timeoutPeriod;

  RecursivePeerRefreshState(
      final BytesValue target,
      final PeerBlacklist peerBlacklist,
      final NodeWhitelistController peerWhitelist,
      final PingDispatcher bondingAgent,
      final FindNeighbourDispatcher neighborFinder,
      final int timeoutPeriod) {
    this.target = target;
    this.peerBlacklist = peerBlacklist;
    this.peerWhitelist = peerWhitelist;
    this.pingDispatcher = bondingAgent;
    this.findNeighbourDispatcher = neighborFinder;
    this.timeoutPeriod = timeoutPeriod;
    this.oneTrueMap = new TreeMap<>();
  }

  public BytesValue getTarget() {
    return target;
  }

  public SortedMap<BytesValue, MetadataPeer> getOneTrueMap() {
    return oneTrueMap;
  }

  void start() {
    bondingInitiateRound();
  }

  void kickstartBootstrapPeers(final List<DiscoveryPeer> bootstrapPeers) {
    for (DiscoveryPeer bootstrapPeer : bootstrapPeers) {
      final MetadataPeer iterationParticipant =
          new MetadataPeer(bootstrapPeer, distance(target, bootstrapPeer.getId()));
      oneTrueMap.put(bootstrapPeer.getId(), iterationParticipant);
    }
  }

  private void bondingCancelOutstandingRequests() {
    for (Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.getBondQueried() && !metadataPeer.getBondResponded()) {
        metadataPeer.setBondCancelled();
      }
    }
  }

  private void bondingInitiateRound() {
    final List<DiscoveryPeer> bondingRoundCandidatesList =
        bondingRoundCandidates(oneTrueMap.size(), oneTrueMap);

    for (DiscoveryPeer discoPeer : bondingRoundCandidatesList) {
      final MetadataPeer metadataPeer = oneTrueMap.get(discoPeer.getId());
      metadataPeer.setBondQueried();
      pingDispatcher.ping(discoPeer);
    }

    final Runnable bondingCancellationTimerTask = this::bondingCancelOutstandingRequests;
    scheduledExecutorService.schedule(
        bondingCancellationTimerTask, timeoutPeriod, TimeUnit.SECONDS);
  }

  private void neighboursCancelOutstandingRequests() {
    for (Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.getNeighbourQueried() && !metadataPeer.getNeighbourResponded()) {
        metadataPeer.setNeighbourCancelled();
      }
    }
  }

  private void neighboursInitiateRound() {
    // TODO: Terminating condition...

    final List<DiscoveryPeer> neighboursRoundCandidatesList =
        neighboursRoundCandidates(3, oneTrueMap);

    for (DiscoveryPeer discoPeer : neighboursRoundCandidatesList) {
      findNeighbourDispatcher.findNeighbours(discoPeer, target);
      final MetadataPeer metadataPeer = oneTrueMap.get(discoPeer.getId());
      metadataPeer.setNeighbourQueried();
    }

    final Runnable neighboursCancellationTimerTask = this::neighboursCancelOutstandingRequests;
    scheduledExecutorService.schedule(
        neighboursCancellationTimerTask, timeoutPeriod, TimeUnit.SECONDS);
  }

  /**
   * What we're doing here is indicating that the message sender (peer), has responded to our
   * outgoing request for nodes with a neighbours packet. Moreover, we examine that packet, and for
   * each one of it's constituent nodes, if we've not hitherto encountered that node, we add it to
   * our one true map.
   */
  void onNeighboursPacketReceived(
      final DiscoveryPeer peer, final NeighborsPacketData neighboursPacket) {
    final MetadataPeer metadataPeer = oneTrueMap.get(peer.getId());
    if (metadataPeer == null) {
      return;
    }
    final List<DiscoveryPeer> receivedPeerList = neighboursPacket.getNodes();
    for (DiscoveryPeer receivedDiscoPeer : receivedPeerList) {
      if (!oneTrueMap.containsKey(receivedDiscoPeer.getId())
          && !peerBlacklist.contains(receivedDiscoPeer)
          && peerWhitelist.contains(receivedDiscoPeer)) {

        final MetadataPeer receivedMetadataPeer =
            new MetadataPeer(receivedDiscoPeer, distance(target, receivedDiscoPeer.getId()));
        oneTrueMap.put(receivedDiscoPeer.getId(), receivedMetadataPeer);
      }
    }
    metadataPeer.setNeighbourResponded();

    if (neighboursRoundTermination()) {
      bondingInitiateRound();
    }
  }

  void onPongPacketReceived(final DiscoveryPeer peer) {
    final MetadataPeer iterationParticipant = oneTrueMap.get(peer.getId());
    iterationParticipant.setBondResponded();
    if (bondingRoundTermination()) {
      neighboursInitiateRound();
    }
  }

  private boolean neighboursRoundTermination() {
    for (Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.getNeighbourQueried()
          && !(metadataPeer.getNeighbourResponded() || metadataPeer.getNeighbourCancelled())) {
        return false;
      }
    }
    return true;
  }

  private boolean bondingRoundTermination() {
    for (Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.getBondQueried()
          && !(metadataPeer.getBondResponded() || metadataPeer.getBondCancelled())) {
        return false;
      }
    }
    return true;
  }

  private List<DiscoveryPeer> bondingRoundCandidates(
      final int max, final SortedMap<BytesValue, MetadataPeer> source) {
    final int threshold = Math.min(oneTrueMap.size(), max);
    final List<DiscoveryPeer> candidatesList = new ArrayList<>();

    int count = 0;
    for (Map.Entry<BytesValue, MetadataPeer> candidateEntry : source.entrySet()) {
      if (count >= threshold) {
        break;
      }
      final MetadataPeer candidate = candidateEntry.getValue();
      if (!candidate.getNeighbourCancelled()
          && !candidate.getNeighbourResponded()
          && !candidate.getNeighbourQueried()
          && !candidate.getBondCancelled()
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
    final int threshold = Math.min(oneTrueMap.size(), max);
    final List<DiscoveryPeer> candidatesList = new ArrayList<>();

    int count = 0;
    for (Map.Entry<BytesValue, MetadataPeer> candidateEntry : source.entrySet()) {
      if (count >= threshold) {
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

  public static class MetadataPeer implements Comparable<MetadataPeer> {
    DiscoveryPeer peer;
    Integer distance;

    boolean bondQueried;
    boolean bondResponded;
    boolean bondCancelled;

    boolean neighbourQueried;
    boolean neighbourResponded;
    boolean neighbourCancelled;

    @Override
    public int compareTo(final MetadataPeer o) {
      if (this.distance > o.distance) return 1;
      return -1;
    }

    public MetadataPeer(final DiscoveryPeer peer, final Integer distance) {
      this.peer = peer;
      this.distance = distance;

      this.bondQueried = false;
      this.bondResponded = false;
      this.bondCancelled = false;

      this.neighbourQueried = false;
      this.neighbourResponded = false;
      this.neighbourCancelled = false;
    }

    DiscoveryPeer getPeer() {
      return peer;
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

    void setBondCancelled() {
      this.bondCancelled = true;
    }

    boolean getBondCancelled() {
      return bondCancelled;
    }

    /* * */

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
  public interface PingDispatcher {
    /**
     * Initiates a bonding PING-PONG cycle with a peer.
     *
     * @param peer The targeted peer.
     */
    void ping(final DiscoveryPeer peer);
  }
}
