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

import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    commenceTimeoutTask();
  }

  private void commenceTimeoutTask() {
    TimerTask timeoutTask =
        new TimerTask() {
          @Override
          public void run() {
            List<OutstandingRequest> outstandingRequestListCopy =
                new ArrayList<>(outstandingRequestList);

            for (OutstandingRequest outstandingRequest : outstandingRequestListCopy) {
              if (outstandingRequest.isExpired()) {
                List<Peer> queryCandidates = determineFindNodeCandidates(anteList.size());
                for (Peer candidate : queryCandidates) {
                  if (!contactedInCurrentExecution.contains(candidate.getId())
                      && !outstandingRequestList.contains(new OutstandingRequest(candidate))) {
                    executeFindNodeRequest(candidate);
                  }
                }
                outstandingRequestList.remove(outstandingRequest);
              }
            }
          }
        };
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executor.scheduleAtFixedRate(timeoutTask, 0, 2, TimeUnit.SECONDS);
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
      // The lookup terminates when the initiator has queried
      // and gotten responses from the k closest nodes it has seen.
    }
  }

  void digestNeighboursPacket(final NeighborsPacketData neighboursPacket, final Peer peer) {
    if (outstandingRequestList.contains(new OutstandingRequest(peer))) {
      List<Peer> receivedPeerList = neighboursPacket.getNodes();
      for (Peer receivedPeer : receivedPeerList) {
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

  void kickstartBootstrapPeers(final List<Peer> bootstrapPeers) {
    for (Peer bootstrapPeer : bootstrapPeers) {
      BytesValue peerId = bootstrapPeer.getId();
      outstandingRequestList.add(new OutstandingRequest(bootstrapPeer));
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

  static class OutstandingRequest {
    Instant creation;
    Peer peer;

    OutstandingRequest(final Peer peer) {
      this.creation = Instant.now();
      this.peer = peer;
    }

    Peer getPeer() {
      return peer;
    }

    boolean isExpired() {
      Duration duration = Duration.between(creation, Instant.now());
      Duration limit = Duration.ofSeconds(5);
      return duration.compareTo(limit) >= 0;
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
