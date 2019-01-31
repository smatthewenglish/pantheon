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
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryStatus;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RecursivePeerRefreshState {
  private static final Logger LOG = LogManager.getLogger();
  private static final int MAX_CONCURRENT_REQUESTS = 3;
  private BytesValue target;
  private final PeerBlacklist peerBlacklist;
  private final NodeWhitelistController peerWhitelist;

  private final BondingAgent bondingAgent;
  private final FindNeighbourDispatcher findNeighbourDispatcher;
  private Optional<RoundTimeout> currentRoundTimeout = Optional.empty();
  private boolean iterativeSearchInProgress = false;

  private int currentRound = 0;

  private final SortedMap<BytesValue, MetadataPeer> oneTrueMap = new TreeMap<>();

  // TODO: Need to shut these down as part of Pantheon stop
  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor();
  private final int timeoutPeriod;

  RecursivePeerRefreshState(
      final PeerBlacklist peerBlacklist,
      final NodeWhitelistController peerWhitelist,
      final BondingAgent bondingAgent,
      final FindNeighbourDispatcher neighborFinder,
      final int timeoutPeriod) {
    this.peerBlacklist = peerBlacklist;
    this.peerWhitelist = peerWhitelist;
    this.bondingAgent = bondingAgent;
    this.findNeighbourDispatcher = neighborFinder;
    this.timeoutPeriod = timeoutPeriod;
  }

  synchronized void start(final List<DiscoveryPeer> initialPeers, final BytesValue target) {
    if (iterativeSearchInProgress) {
      LOG.debug("Skipping discovery because previous search is still in progress.");
      return;
    }
    iterativeSearchInProgress = true;
    this.target = target;
    currentRoundTimeout.ifPresent(RoundTimeout::cancelTimeout);
    oneTrueMap.clear();
    addInitialPeers(initialPeers);
    bondingInitiateRound();
  }

  synchronized void stop() {
    scheduledExecutorService.shutdownNow();
  }

  private boolean refreshReachedTerminationCieling() {
    final int cielingRound = 100;
    return currentRound > cielingRound;
  }

  private void addInitialPeers(final List<DiscoveryPeer> initialPeers) {
    for (final DiscoveryPeer peer : initialPeers) {
      final MetadataPeer iterationParticipant =
          new MetadataPeer(peer, distance(target, peer.getId()));
      oneTrueMap.put(peer.getId(), iterationParticipant);
    }
  }

  private void bondingInitiateRound() {
    currentRoundTimeout.ifPresent(RoundTimeout::cancelTimeout);
    final List<DiscoveryPeer> candidates = bondingRoundCandidates();
    if (candidates.isEmpty()) {
      // All peers are already bonded (or failed to bond) so immediately switch to neighbours round
      LOG.debug("Skipping bonding round because no candidates are available");
      neighboursInitiateRound();
      return;
    }
    LOG.debug("Initiating bonding round with {} candidates", candidates.size());
    for (final DiscoveryPeer discoPeer : candidates) {
      bondingAgent.performBonding(discoPeer, false);
    }
    currentRoundTimeout = Optional.of(scheduleTimeout(this::bondingCancelOutstandingRequests));
  }

  private RoundTimeout scheduleTimeout(final Runnable onTimeout) {
    final AtomicBoolean timeoutCancelled = new AtomicBoolean(false);
    final ScheduledFuture<?> future =
        scheduledExecutorService.schedule(
            () -> performIfNotCancelled(onTimeout, timeoutCancelled),
            this.timeoutPeriod,
            TimeUnit.SECONDS);
    return new RoundTimeout(timeoutCancelled, future);
  }

  private synchronized void performIfNotCancelled(
      final Runnable action, final AtomicBoolean cancelled) {
    if (!cancelled.get()) {
      action.run();
    }
  }

  private void bondingCancelOutstandingRequests() {
    LOG.debug("Bonding round timed out");
    for (final Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.hasOutstandingBondRequest()) {
        metadataPeer.bondingFailed();
      }
    }
    neighboursInitiateRound();
  }

  private void neighboursInitiateRound() {
    if (refreshReachedTerminationCieling()) {
      return;
    }
    currentRoundTimeout.ifPresent(RoundTimeout::cancelTimeout);
    final List<DiscoveryPeer> candidates = neighboursRoundCandidates();
    if (candidates.isEmpty()) {
      LOG.debug("Iterative peer search complete");
      iterativeSearchInProgress = false;
      return;
    }
    LOG.debug(
        "Initiating neighbours round with {} candidates from {} tracked nodes",
        candidates.size(),
        oneTrueMap.size());
    for (final DiscoveryPeer discoPeer : candidates) {
      findNeighbourDispatcher.findNeighbours(discoPeer, target);
      final MetadataPeer metadataPeer = oneTrueMap.get(discoPeer.getId());
      metadataPeer.findNeighboursStarted();
    }
    currentRoundTimeout = Optional.of(scheduleTimeout(this::neighboursCancelOutstandingRequests));
    currentRound++;
  }

  private synchronized void neighboursCancelOutstandingRequests() {
    LOG.debug("Neighbours round timed out");
    for (final Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.hasOutstandingNeighboursRequest()) {
        metadataPeer.findNeighboursFailed();
      }
    }
    bondingInitiateRound();
  }

  synchronized void onNeighboursPacketReceived(
      final DiscoveryPeer peer, final NeighborsPacketData neighboursPacket) {
    final MetadataPeer metadataPeer = oneTrueMap.get(peer.getId());
    if (metadataPeer == null) {
      return;
    }
    LOG.debug("Received neighbours packet with {} neighbours", neighboursPacket.getNodes().size());
    for (final DiscoveryPeer receivedDiscoPeer : neighboursPacket.getNodes()) {
      if (!oneTrueMap.containsKey(receivedDiscoPeer.getId())
          && !peerBlacklist.contains(receivedDiscoPeer)
          && peerWhitelist.contains(receivedDiscoPeer)) {

        final MetadataPeer receivedMetadataPeer =
            new MetadataPeer(receivedDiscoPeer, distance(target, receivedDiscoPeer.getId()));
        oneTrueMap.put(receivedDiscoPeer.getId(), receivedMetadataPeer);
      }
    }
    metadataPeer.findNeighboursComplete();

    if (neighboursRoundTermination()) {
      bondingInitiateRound();
    }
  }

  synchronized void onBondingComplete(final DiscoveryPeer peer) {
    final MetadataPeer iterationParticipant = oneTrueMap.get(peer.getId());
    if (iterationParticipant == null) {
      return;
    }
    if (bondingRoundTermination()) {
      neighboursInitiateRound();
    }
  }

  private boolean neighboursRoundTermination() {
    for (final Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.hasOutstandingNeighboursRequest()) {
        return false;
      }
    }
    return true;
  }

  private boolean bondingRoundTermination() {
    for (final Map.Entry<BytesValue, MetadataPeer> entry : oneTrueMap.entrySet()) {
      final MetadataPeer metadataPeer = entry.getValue();
      if (metadataPeer.hasOutstandingBondRequest()) {
        return false;
      }
    }
    return true;
  }

  private List<DiscoveryPeer> bondingRoundCandidates() {
    return oneTrueMap
        .values()
        .stream()
        .filter(MetadataPeer::isBondingCandidate)
        .map(MetadataPeer::getPeer)
        .collect(Collectors.toList());
  }

  private List<DiscoveryPeer> neighboursRoundCandidates() {
    return oneTrueMap
        .values()
        .stream()
        .filter(MetadataPeer::isNeighboursRoundCandidate)
        .limit(MAX_CONCURRENT_REQUESTS)
        .map(MetadataPeer::getPeer)
        .collect(Collectors.toList());
  }

  @VisibleForTesting
  public BytesValue getTarget() {
    return target;
  }

  @VisibleForTesting
  void cancelCurrentRound() {
    iterativeSearchInProgress = false;
  }

  public static class MetadataPeer implements Comparable<MetadataPeer> {
    DiscoveryPeer peer;
    Integer distance;

    boolean bondingFailed = false;
    boolean findNeighboursStarted = false;
    boolean findNeighboursComplete = false;

    public MetadataPeer(final DiscoveryPeer peer, final Integer distance) {
      this.peer = peer;
      this.distance = distance;
    }

    DiscoveryPeer getPeer() {
      return peer;
    }

    void bondingFailed() {
      this.bondingFailed = true;
    }

    void findNeighboursStarted() {
      this.findNeighboursStarted = true;
    }

    void findNeighboursComplete() {
      this.findNeighboursComplete = true;
    }

    void findNeighboursFailed() {
      this.findNeighboursComplete = true;
    }

    private boolean isBondingCandidate() {
      return !bondingFailed
          && !peer.getStatus().equals(PeerDiscoveryStatus.BONDED)
          && !peer.getStatus().equals(PeerDiscoveryStatus.BONDING);
    }

    private boolean isNeighboursRoundCandidate() {
      return peer.getStatus().equals(PeerDiscoveryStatus.BONDED) && !findNeighboursStarted;
    }

    private boolean hasOutstandingBondRequest() {
      return peer.getStatus().equals(PeerDiscoveryStatus.BONDING)
          && !peer.getStatus().equals(PeerDiscoveryStatus.BONDED)
          && !bondingFailed;
    }

    private boolean hasOutstandingNeighboursRequest() {
      return findNeighboursStarted && !findNeighboursComplete;
    }

    @Override
    public int compareTo(final MetadataPeer o) {
      if (this.distance > o.distance) {
        return 1;
      }
      return -1;
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

  private static class RoundTimeout {
    private final AtomicBoolean timeoutCancelled;
    private final ScheduledFuture<?> future;

    private RoundTimeout(final AtomicBoolean timeoutCancelled, final ScheduledFuture<?> future) {
      this.timeoutCancelled = timeoutCancelled;
      this.future = future;
    }

    public void cancelTimeout() {
      future.cancel(false);
      timeoutCancelled.set(true);
    }
  }
}
