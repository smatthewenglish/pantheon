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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.toList;
import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDistanceCalculator.distance;

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

        //commenceTimeoutTask();
    }

    /**
     * The lookup initiator starts by picking CONCURRENT_REQUEST_LIMIT closest nodes to the target it
     * knows of. The initiator then issues concurrent FindNode packets to those nodes.
     */
    private void initiatePeerRefreshCycle(final List<Peer> peers) {
        for (Peer peer : peers) {
            if (!contactedInCurrentExecution.contains(peer.getId())) {
                BytesValue peerId = peer.getId();
                outstandingRequestList.add(new OutstandingRequest(peer.getId()));
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
                    anteList.add(new PeerDistance(receivedPeer, distance(target, receivedPeer.getId())));
                }
            }
            outstandingRequestList.remove(new OutstandingRequest(peerIdentifier));
            performIteration();
        }
    }

    private List<Peer> determineFindNodeCandidates(final int from, final int threshold) {
        anteList.sort(
                (peer1, peer2) -> {
                    if (peer1.getDistance() > peer2.getDistance()) return 1;
                    if (peer1.getDistance() < peer2.getDistance()) return -1;
                    return 0;
                });
        return anteList
                .subList(from, threshold)
                .stream()
                .map(PeerDistance::getPeer)
                .collect(toList());
    }

    private void performIteration() {
        if (outstandingRequestList.isEmpty()) {
            List<Peer> queryCandidates = determineFindNodeCandidates(0, CONCURRENT_REQUEST_LIMIT);
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

//    private void commenceTimeoutTask() {
//        TimerTask timeoutTask = new TimerTask() {
//            public void run() {
//                for (OutstandingRequest outstandingRequest : outstandingRequestList) {
//                    if (outstandingRequest.isExpired()) {
//                        // Replace with next highest...
//                        List<Peer> queryCandidates = determineFindNodeCandidates(0, anteList.size());
//                        for (Peer peer : queryCandidates) {
//                            if (!outstandingRequestList.contains(peer) && !contactedInCurrentExecution.contains(peer.getId())) {
//                                contactedInCurrentExecution.add(peer.getId());
//                                bondingAgent.performBonding(peer);
//                                neighborFinder.issueFindNodeRequest(peer);
//                                // Remove the expired request from our consideration...
//                                outstandingRequestList.remove(outstandingRequest);
//                                contactedInCurrentExecution.add(outstandingRequest.peerId);
//                            }
//                        }
//                    }
//                }
//            }
//        };
//        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
//        executor.scheduleAtFixedRate(timeoutTask, 0, 30, TimeUnit.SECONDS);
//    }

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
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            OutstandingRequest that = (OutstandingRequest) o;
            return Objects.equals(peerId, that.peerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(peerId);
        }

        @Override
        public String toString() {
            return peerId.toString();
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
