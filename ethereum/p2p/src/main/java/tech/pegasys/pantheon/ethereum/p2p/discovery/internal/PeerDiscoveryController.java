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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerTable.AddResult.Outcome;

import tech.pegasys.pantheon.crypto.SECP256K1;
import tech.pegasys.pantheon.crypto.SECP256K1.KeyPair;
import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeer;
import tech.pegasys.pantheon.ethereum.p2p.discovery.DiscoveryPeerStatus;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryEvent;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryEvent.PeerBondedEvent;
import tech.pegasys.pantheon.ethereum.p2p.peers.Peer;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerBlacklist;
import tech.pegasys.pantheon.ethereum.p2p.permissioning.NodeWhitelistController;
import tech.pegasys.pantheon.util.Subscribers;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This component is the entrypoint for managing the lifecycle of peers.
 *
 * <p>It keeps track of the interactions with each peer, including the expectations of what we
 * expect to receive next from each peer. In other words, it implements the state machine for
 * (discovery) peers.
 *
 * <p>When necessary, it updates the underlying {@link PeerTable}, particularly with additions which
 * may succeed or not depending on the contents of the target bucket for the peer.
 *
 * <h3>Peer state machine</h3>
 *
 * <pre>{@code
 *                                                                +--------------------+
 *                                                                |                    |
 *                                                    +-----------+  MESSAGE_EXPECTED  +-----------+
 *                                                    |           |                    |           |
 *                                                    |           +---+----------------+           |
 * +------------+         +-----------+         +-----+----+          |                      +-----v-----+
 * |            |         |           |         |          <----------+                      |           |
 * |  KNOWN  +--------->  BONDING  +--------->  BONDED     |                                 |  DROPPED  |
 * |            |         |           |         |          ^                                 |           |
 * +------------+         +-----------+         +----------+                                 +-----------+
 *
 * }</pre>
 *
 * <ul>
 *   <li><em>KNOWN:</em> the peer is known but there is no ongoing interaction with it.
 *   <li><em>BONDING:</em> an attempt to ping is being made (e.g. a PING has been sent).
 *   <li><em>BONDED:</em> the bonding handshake has taken place (e.g. an expected PONG has been
 *       received after having sent a PING or a PING has been received and a PONG has been sent in
 *       response). This is the same as having an "active" channel.
 *   <li><em>MESSAGE_EXPECTED (*)</em>: a message has been sent and a response is expected.
 *   <li><em>DROPPED (*):</em> the peer is no longer in our peer table.
 * </ul>
 *
 * <p>(*) It is worthy to note that the <code>MESSAGE_EXPECTED</code> and <code>DROPPED</code>
 * states are not modelled explicitly in {@link DiscoveryPeerStatus}, but they have been included in
 * the diagram for clarity. These two states define the elimination path for a peer from the
 * underlying table.
 *
 * <p>If an expectation to receive a message was unmet, following the evaluation of a failure
 * condition, the peer will be physically dropped (eliminated) from the table.
 */
public class PeerDiscoveryController {

  private static final Logger LOG = LogManager.getLogger();
  private static final long REFRESH_CHECK_INTERVAL_MILLIS = MILLISECONDS.convert(30, SECONDS);
  private final TimerUtil timerUtil;
  private final PeerTable peerTable;
  private final Collection<DiscoveryPeer> bootstrapNodes;
  private final AtomicBoolean started = new AtomicBoolean(false);

  private final SECP256K1.KeyPair keypair;
  private final DiscoveryPeer localPeer; // The peer representation of this node
  private final OutboundMessageHandler outboundMessageHandler;
  private final PeerBlacklist peerBlacklist;
  private final NodeWhitelistController nodeWhitelist;

  private final PeerRequirement peerRequirement;
  private final long tableRefreshIntervalMs;
  private long lastRefreshTime = -1;
  private OptionalLong tableRefreshTimerId = OptionalLong.empty();
  private final Subscribers<Consumer<PeerBondedEvent>>
      peerBondedObservers; // Observers for "peer bonded" discovery events.

  private final BytesValue target = Peer.randomId();
  private RecursivePeerRefreshState recursivePeerRefreshState;

  public PeerDiscoveryController(
      final KeyPair keypair,
      final DiscoveryPeer localPeer,
      final PeerTable peerTable,
      final Collection<DiscoveryPeer> bootstrapNodes,
      final OutboundMessageHandler outboundMessageHandler,
      final TimerUtil timerUtil,
      final long tableRefreshIntervalMs,
      final PeerRequirement peerRequirement,
      final PeerBlacklist peerBlacklist,
      final NodeWhitelistController nodeWhitelist,
      final Subscribers<Consumer<PeerBondedEvent>> peerBondedObservers) {
    this.timerUtil = timerUtil;
    this.keypair = keypair;
    this.localPeer = localPeer;
    this.bootstrapNodes = bootstrapNodes;
    this.peerTable = peerTable;
    this.tableRefreshIntervalMs = tableRefreshIntervalMs;
    this.peerRequirement = peerRequirement;
    this.peerBlacklist = peerBlacklist;
    this.nodeWhitelist = nodeWhitelist;
    this.outboundMessageHandler = outboundMessageHandler;
    this.peerBondedObservers = peerBondedObservers;
  }

  @VisibleForTesting
  public RecursivePeerRefreshState getRecursivePeerRefreshState() {
    return recursivePeerRefreshState;
  }

  @VisibleForTesting
  public BytesValue getTarget() {
    return target;
  }

  public CompletableFuture<?> start() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("The peer table had already been started");
    }
    bootstrapNodes.stream().filter(nodeWhitelist::contains).forEach(peerTable::tryAdd);
    recursivePeerRefreshState =
        new RecursivePeerRefreshState(
            target,
            peerBlacklist,
            nodeWhitelist,
            this::dispatchPing,
            this::dispatchFindNeighbours,
            30);
    recursivePeerRefreshState.kickstartBootstrapPeers(
        bootstrapNodes.stream().filter(nodeWhitelist::contains).collect(Collectors.toList()));
    recursivePeerRefreshState.start();

    final long timerId =
        timerUtil.setPeriodic(
            Math.min(REFRESH_CHECK_INTERVAL_MILLIS, tableRefreshIntervalMs),
            () -> refreshTableIfRequired());
    tableRefreshTimerId = OptionalLong.of(timerId);

    return CompletableFuture.completedFuture(null);
  }

  public CompletableFuture<?> stop() {
    if (!started.compareAndSet(true, false)) {
      return CompletableFuture.completedFuture(null);
    }

    tableRefreshTimerId.ifPresent(timerUtil::cancelTimer);
    tableRefreshTimerId = OptionalLong.empty();
    return CompletableFuture.completedFuture(null);
  }

  /**
   * Handles an incoming message and processes it based on the state machine for the {@link
   * DiscoveryPeer}.
   *
   * <p>The callback will be called with the canonical representation of the sender Peer as stored
   * in our table, or with an empty Optional if the message was out of band and we didn't process
   * it.
   *
   * @param packet The incoming message.
   * @param sender The sender.
   */
  public void onMessage(final Packet packet, final DiscoveryPeer sender) {
    LOG.trace(
        "<<< Received {} discovery packet from {} ({}): {}",
        packet.getType(),
        sender.getEndpoint(),
        sender.getId().slice(0, 16),
        packet);
    // Message from self. This should not happen.
    if (sender.getId().equals(localPeer.getId())) {
      return;
    }
    if (!nodeWhitelist.isPermitted(sender)) {
      return;
    }
    // Load the peer from the table, or use the instance that comes in.
    final Optional<DiscoveryPeer> maybeKnownPeer = peerTable.get(sender);
    final DiscoveryPeer peer = maybeKnownPeer.orElse(sender);
    final boolean peerKnown = maybeKnownPeer.isPresent();
    final boolean peerBlacklisted = peerBlacklist.contains(peer);

    if (peerBlacklisted) {
      return;
    }

    final long now = System.currentTimeMillis();
    if (peer.getFirstDiscovered() == 0) {
      peer.setFirstDiscovered(now);
    }
    peer.setLastSeen(now);

    switch (packet.getType()) {
      case PING:
        peer.setStatus(DiscoveryPeerStatus.RECEIVED_PING_FROM);
        if (addToPeerTable(peer)) {
          final PingPacketData ping = packet.getPacketData(PingPacketData.class).get();
          respondToPing(peer, ping, packet.getHash());
        }
        break;
      case PONG:
        notifyPeerBonded(peer, now);
        peer.setStatus(DiscoveryPeerStatus.RECEIVED_PONG_FROM);
        addToPeerTable(peer);
        recursivePeerRefreshState.onPongPacketReceived(peer);
        break;
      case NEIGHBORS:
        peer.setStatus(DiscoveryPeerStatus.RECEIVED_NEIGHBOURS_FROM);
        recursivePeerRefreshState.onNeighboursPacketReceived(
            peer, packet.getPacketData(NeighborsPacketData.class).orElse(null));
        break;
      case FIND_NEIGHBORS:
        if (!peerKnown) {
          break;
        }
        final FindNeighborsPacketData findNeighborsPacketData =
            packet.getPacketData(FindNeighborsPacketData.class).get();
        respondToFindNeighbors(peer, findNeighborsPacketData);
        break;
    }
  }

  private boolean addToPeerTable(final DiscoveryPeer peer) {
    final PeerTable.AddResult result = peerTable.tryAdd(peer);
    if (result.getOutcome() == Outcome.SELF) {
      return false;
    }
    if (result.getOutcome() == Outcome.ALREADY_EXISTED) {
      peerTable.evict(peer); // Bump peer.
      peerTable.tryAdd(peer);
    } else if (result.getOutcome() == Outcome.BUCKET_FULL) {
      peerTable.evict(result.getEvictionCandidate());
      peerTable.tryAdd(peer);
    }
    return true;
  }

  private void refreshTableIfRequired() {
    final long now = System.currentTimeMillis();
    if (lastRefreshTime + tableRefreshIntervalMs < now) {
      LOG.info("Peer table refresh triggered by timer expiry");
      refreshTable();
    } else if (!peerRequirement.hasSufficientPeers()) {
      LOG.info("Peer table refresh triggered by insufficient peers");
      refreshTable();
    }
  }

  /** Refreshes the peer table by interrogating the closest nodes for a random search target. */
  private void refreshTable() {
    final List<DiscoveryPeer> nearestPeers = peerTable.nearestPeers(Peer.randomId(), 16);
    recursivePeerRefreshState.kickstartBootstrapPeers(nearestPeers);
    recursivePeerRefreshState.start();
    lastRefreshTime = System.currentTimeMillis();
  }

  private void sendPacket(final DiscoveryPeer peer, final PacketType type, final PacketData data) {
    final Packet packet = createPacket(type, data);
    outboundMessageHandler.send(peer, packet);
  }

  @VisibleForTesting
  Packet createPacket(final PacketType type, final PacketData data) {
    return Packet.create(type, data, keypair);
  }

  /**
   * Initiates a bonding PING-PONG cycle with a peer.
   *
   * @param peer The targeted peer.
   */
  void dispatchPing(final DiscoveryPeer peer) {
    peer.setFirstDiscovered(System.currentTimeMillis());
    final PingPacketData pingPacketData =
        PingPacketData.create(localPeer.getEndpoint(), peer.getEndpoint());
    sendPacket(peer, PacketType.PING, pingPacketData);
    peer.setStatus(DiscoveryPeerStatus.DISPATCHED_PING_TO);
  }

  /**
   * Sends a FIND_NEIGHBORS message to a {@link DiscoveryPeer}, in search of a target value.
   *
   * @param peer the peer to interrogate
   * @param target the target node ID to find
   */
  private void dispatchFindNeighbours(final DiscoveryPeer peer, final BytesValue target) {
    final FindNeighborsPacketData findNeighborsPacketData = FindNeighborsPacketData.create(target);
    sendPacket(peer, PacketType.FIND_NEIGHBORS, findNeighborsPacketData);
    peer.setStatus(DiscoveryPeerStatus.DISPATCHED_FIND_NEIGHBOURS_TO);
  }

  private void respondToPing(
      final DiscoveryPeer peer, final PingPacketData packetData, final BytesValue pingHash) {
    final PongPacketData pongPacketData = PongPacketData.create(packetData.getFrom(), pingHash);
    sendPacket(peer, PacketType.PONG, pongPacketData);
    peer.setStatus(DiscoveryPeerStatus.DISPATCHED_PONG_TO);
    notifyPeerBonded(peer, System.currentTimeMillis());
  }

  private void respondToFindNeighbors(
      final DiscoveryPeer peer, final FindNeighborsPacketData packetData) {
    // TODO: for now return 16 peers. Other implementations calculate how many
    // peers they can fit in a 1280-byte payload.
    final List<DiscoveryPeer> peers = peerTable.nearestPeers(packetData.getTarget(), 16);
    final NeighborsPacketData neighborsPacketData = NeighborsPacketData.create(peers);
    sendPacket(peer, PacketType.NEIGHBORS, neighborsPacketData);
    peer.setStatus(DiscoveryPeerStatus.DISPATCHED_NEIGHBOURS_TO);
  }

  private void notifyPeerBonded(final DiscoveryPeer peer, final long now) {
    final PeerBondedEvent event = new PeerBondedEvent(peer, now);
    dispatchEvent(peerBondedObservers, event);
  }

  // Dispatches an event to a set of observers.
  private <T extends PeerDiscoveryEvent> void dispatchEvent(
      final Subscribers<Consumer<T>> observers, final T event) {
    observers.forEach(observer -> observer.accept(event));
  }

  /**
   * Returns a copy of the known peers. Modifications to the list will not update the table's state,
   * but modifications to the Peers themselves will.
   *
   * @return List of peers.
   */
  public Collection<DiscoveryPeer> getPeers() {
    return peerTable.getAllPeers();
  }
}
