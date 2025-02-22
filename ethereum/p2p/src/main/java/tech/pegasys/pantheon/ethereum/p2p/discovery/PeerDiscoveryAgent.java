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
package tech.pegasys.pantheon.ethereum.p2p.discovery;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static tech.pegasys.pantheon.util.bytes.BytesValue.wrapBuffer;

import tech.pegasys.pantheon.crypto.SECP256K1;
import tech.pegasys.pantheon.ethereum.p2p.config.DiscoveryConfiguration;
import tech.pegasys.pantheon.ethereum.p2p.discovery.PeerDiscoveryEvent.PeerBondedEvent;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.Packet;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDiscoveryController;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerDiscoveryController.AsyncExecutor;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PeerRequirement;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.PingPacketData;
import tech.pegasys.pantheon.ethereum.p2p.discovery.internal.TimerUtil;
import tech.pegasys.pantheon.ethereum.p2p.peers.PeerId;
import tech.pegasys.pantheon.ethereum.p2p.permissions.PeerPermissions;
import tech.pegasys.pantheon.ethereum.permissioning.node.NodePermissioningController;
import tech.pegasys.pantheon.metrics.MetricsSystem;
import tech.pegasys.pantheon.util.NetworkUtility;
import tech.pegasys.pantheon.util.Subscribers;
import tech.pegasys.pantheon.util.bytes.BytesValue;
import tech.pegasys.pantheon.util.enode.EnodeURL;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.InetAddresses;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The peer discovery agent is the network component that sends and receives peer discovery messages
 * via UDP.
 */
public abstract class PeerDiscoveryAgent {
  protected static final Logger LOG = LogManager.getLogger();

  // The devp2p specification says only accept packets up to 1280, but some
  // clients ignore that, so we add in a little extra padding.
  private static final int MAX_PACKET_SIZE_BYTES = 1600;

  protected final List<DiscoveryPeer> bootstrapPeers;
  private final List<PeerRequirement> peerRequirements = new CopyOnWriteArrayList<>();
  private final PeerPermissions peerPermissions;
  private final Optional<NodePermissioningController> nodePermissioningController;
  private final MetricsSystem metricsSystem;
  /* The peer controller, which takes care of the state machine of peers. */
  protected Optional<PeerDiscoveryController> controller = Optional.empty();

  /* The keypair used to sign messages. */
  protected final SECP256K1.KeyPair keyPair;
  private final BytesValue id;
  protected final DiscoveryConfiguration config;

  /* This is the {@link tech.pegasys.pantheon.ethereum.p2p.Peer} object holding who we are. */
  private DiscoveryPeer advertisedPeer;

  /* Is discovery enabled? */
  private boolean isActive = false;
  private final Subscribers<Consumer<PeerBondedEvent>> peerBondedObservers = new Subscribers<>();

  public PeerDiscoveryAgent(
      final SECP256K1.KeyPair keyPair,
      final DiscoveryConfiguration config,
      final PeerPermissions peerPermissions,
      final Optional<NodePermissioningController> nodePermissioningController,
      final MetricsSystem metricsSystem) {
    this.metricsSystem = metricsSystem;
    checkArgument(keyPair != null, "keypair cannot be null");
    checkArgument(config != null, "provided configuration cannot be null");

    validateConfiguration(config);

    this.peerPermissions = peerPermissions;
    this.nodePermissioningController = nodePermissioningController;
    this.bootstrapPeers =
        config.getBootnodes().stream().map(DiscoveryPeer::fromEnode).collect(Collectors.toList());

    this.config = config;
    this.keyPair = keyPair;

    id = keyPair.getPublicKey().getEncodedBytes();
  }

  protected abstract TimerUtil createTimer();

  protected abstract AsyncExecutor createWorkerExecutor();

  protected abstract CompletableFuture<InetSocketAddress> listenForConnections();

  protected abstract CompletableFuture<Void> sendOutgoingPacket(
      final DiscoveryPeer peer, final Packet packet);

  public abstract CompletableFuture<?> stop();

  public CompletableFuture<?> start(final int tcpPort) {
    if (config.isActive()) {
      final String host = config.getBindHost();
      final int port = config.getBindPort();
      LOG.info("Starting peer discovery agent on host={}, port={}", host, port);

      return listenForConnections()
          .thenAccept(
              (InetSocketAddress localAddress) -> {
                // Once listener is set up, finish initializing
                advertisedPeer =
                    DiscoveryPeer.fromEnode(
                        EnodeURL.builder()
                            .nodeId(id)
                            .ipAddress(config.getAdvertisedHost())
                            .listeningPort(tcpPort)
                            .discoveryPort(localAddress.getPort())
                            .build());
                isActive = true;
                startController();
              });
    } else {
      this.isActive = false;
      return CompletableFuture.completedFuture(null);
    }
  }

  public void addPeerRequirement(final PeerRequirement peerRequirement) {
    this.peerRequirements.add(peerRequirement);
  }

  private void startController() {
    final PeerDiscoveryController controller = createController();
    this.controller = Optional.of(controller);
    controller.start();
  }

  private PeerDiscoveryController createController() {
    return PeerDiscoveryController.builder()
        .keypair(keyPair)
        .localPeer(advertisedPeer)
        .bootstrapNodes(bootstrapPeers)
        .outboundMessageHandler(this::handleOutgoingPacket)
        .timerUtil(createTimer())
        .workerExecutor(createWorkerExecutor())
        .peerRequirement(PeerRequirement.combine(peerRequirements))
        .peerPermissions(peerPermissions)
        .nodePermissioningController(nodePermissioningController)
        .peerBondedObservers(peerBondedObservers)
        .metricsSystem(metricsSystem)
        .build();
  }

  protected boolean validatePacketSize(final int packetSize) {
    return packetSize <= MAX_PACKET_SIZE_BYTES;
  }

  protected void handleIncomingPacket(final Endpoint sourceEndpoint, final Packet packet) {
    OptionalInt tcpPort = OptionalInt.empty();
    if (packet.getPacketData(PingPacketData.class).isPresent()) {
      final PingPacketData ping = packet.getPacketData(PingPacketData.class).orElseGet(null);
      if (ping != null && ping.getFrom() != null && ping.getFrom().getTcpPort().isPresent()) {
        tcpPort = ping.getFrom().getTcpPort();
      }
    }

    // Notify the peer controller.
    String host = sourceEndpoint.getHost();
    int port = sourceEndpoint.getUdpPort();
    final DiscoveryPeer peer =
        DiscoveryPeer.fromEnode(
            EnodeURL.builder()
                .nodeId(packet.getNodeId())
                .ipAddress(host)
                .listeningPort(tcpPort.orElse(port))
                .discoveryPort(port)
                .build());

    controller.ifPresent(c -> c.onMessage(packet, peer));
  }

  /**
   * Send a packet to the given recipient.
   *
   * @param peer the recipient
   * @param packet the packet to send
   */
  protected void handleOutgoingPacket(final DiscoveryPeer peer, final Packet packet) {
    sendOutgoingPacket(peer, packet)
        .whenComplete(
            (res, err) -> {
              if (err != null) {
                if (err instanceof SocketException && err.getMessage().contains("unreachable")) {
                  LOG.debug(
                      "Peer {} is unreachable, packet: {}", peer, wrapBuffer(packet.encode()), err);
                } else {
                  LOG.warn(
                      "Sending to peer {} failed, packet: {}",
                      peer,
                      wrapBuffer(packet.encode()),
                      err);
                }
                return;
              }
              peer.setLastContacted(System.currentTimeMillis());
            });
  }

  public Stream<DiscoveryPeer> streamDiscoveredPeers() {
    return controller.map(PeerDiscoveryController::streamDiscoveredPeers).orElse(Stream.empty());
  }

  public void dropPeer(final PeerId peer) {
    controller.ifPresent(c -> c.dropPeer(peer));
  }

  public Optional<DiscoveryPeer> getAdvertisedPeer() {
    return Optional.ofNullable(advertisedPeer);
  }

  public BytesValue getId() {
    return id;
  }

  /**
   * Adds an observer that will get called when a new peer is bonded with and added to the peer
   * table.
   *
   * <p><i>No guarantees are made about the order in which observers are invoked.</i>
   *
   * @param observer The observer to call.
   * @return A unique ID identifying this observer, to that it can be removed later.
   */
  public long observePeerBondedEvents(final Consumer<PeerBondedEvent> observer) {
    checkNotNull(observer);
    return peerBondedObservers.subscribe(observer);
  }

  /**
   * Removes an previously added peer bonded observer.
   *
   * @param observerId The unique ID identifying the observer to remove.
   * @return Whether the observer was located and removed.
   */
  public boolean removePeerBondedObserver(final long observerId) {
    return peerBondedObservers.unsubscribe(observerId);
  }

  /**
   * Returns the count of observers that are registered on this controller.
   *
   * @return The observer count.
   */
  @VisibleForTesting
  public int getObserverCount() {
    return peerBondedObservers.getSubscriberCount();
  }

  private static void validateConfiguration(final DiscoveryConfiguration config) {
    checkArgument(
        config.getBindHost() != null && InetAddresses.isInetAddress(config.getBindHost()),
        "valid bind host required");
    checkArgument(
        config.getAdvertisedHost() != null
            && InetAddresses.isInetAddress(config.getAdvertisedHost()),
        "valid advertisement host required");
    checkArgument(
        config.getBindPort() == 0 || NetworkUtility.isValidPort(config.getBindPort()),
        "valid port number required");
    checkArgument(config.getBootnodes() != null, "bootstrapPeers cannot be null");
    checkArgument(config.getBucketSize() > 0, "bucket size cannot be negative nor zero");
  }

  /**
   * Returns the current state of the PeerDiscoveryAgent.
   *
   * <p>If true, the node is actively listening for new connections. If false, discovery has been
   * turned off and the node is not listening for connections.
   *
   * @return true, if the {@link PeerDiscoveryAgent} is active on this node, false, otherwise.
   */
  public boolean isActive() {
    return isActive;
  }
}
