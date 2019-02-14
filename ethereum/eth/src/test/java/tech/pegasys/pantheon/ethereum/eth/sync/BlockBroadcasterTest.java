/*
 * Copyright 2019 ConsenSys AG.
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
package tech.pegasys.pantheon.ethereum.eth.sync;

import static java.util.Collections.emptyList;
import static sun.security.krb5.Confounder.bytes;

import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockBody;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.BlockHeaderBuilder;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.LogsBloomFilter;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeers;
import tech.pegasys.pantheon.ethereum.mainnet.MainnetBlockHashFunction;
import tech.pegasys.pantheon.ethereum.p2p.api.MessageData;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection;
import tech.pegasys.pantheon.ethereum.p2p.wire.Capability;
import tech.pegasys.pantheon.ethereum.p2p.wire.PeerInfo;
import tech.pegasys.pantheon.ethereum.p2p.wire.messages.DisconnectMessage;
import tech.pegasys.pantheon.util.bytes.Bytes32;
import tech.pegasys.pantheon.util.bytes.BytesValue;
import tech.pegasys.pantheon.util.uint.UInt256;

import java.net.SocketAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Set;

import org.junit.Test;

public class BlockBroadcasterTest {

  @Test
  public void blockPropagationTest() {
    final String protocolName = "eth";
    final EthPeers ethPeers = new EthPeers(protocolName);

    final BytesValue id0 = createId(0);
    final PeerConnection peerConnection0 = new StubbedPeerConnection(id0);

    final BytesValue id1 = createId(1);
    final PeerConnection peerConnection1 = new StubbedPeerConnection(id1);

    final BytesValue id2 = createId(2);
    final PeerConnection peerConnection2 = new StubbedPeerConnection(id2);

    ethPeers.registerConnection(peerConnection0);
    EthPeer ethPeer0 = ethPeers.peer(peerConnection0);
    ethPeer0.registerStatusSent();
    ethPeer0.registerStatusReceived(Hash.ZERO, UInt256.ZERO);

    ethPeers.registerConnection(peerConnection1);
    EthPeer ethPeer1 = ethPeers.peer(peerConnection1);
    ethPeer1.registerStatusSent();
    ethPeer1.registerStatusReceived(Hash.ZERO, UInt256.ZERO);

    ethPeers.registerConnection(peerConnection2);
    EthPeer ethPeer2 = ethPeers.peer(peerConnection2);
    ethPeer2.registerStatusSent();
    ethPeer2.registerStatusReceived(Hash.ZERO, UInt256.ZERO);

    EthContext ethContext = new EthContext(protocolName, ethPeers, null, null);

    BlockBroadcaster blockBroadcaster = new BlockBroadcaster(ethContext);

    Block block = generateBlock();
    blockBroadcaster.propagate(block, UInt256.ZERO);
  }

  private Block generateBlock() {
    final BlockHeader header =
        BlockHeaderBuilder.create()
            .parentHash(Hash.ZERO)
            .ommersHash(Hash.ZERO)
            .coinbase(Address.fromHexString("0x0000000000000000000000000000000000000000"))
            .stateRoot(Hash.ZERO)
            .transactionsRoot(Hash.ZERO)
            .receiptsRoot(Hash.ZERO)
            .logsBloom(new LogsBloomFilter(BytesValue.of(bytes(LogsBloomFilter.BYTE_SIZE))))
            .difficulty(UInt256.ZERO)
            .number(0L)
            .gasLimit(1L)
            .gasUsed(1L)
            .timestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).getEpochSecond())
            .extraData(Bytes32.wrap(bytes(Bytes32.SIZE)))
            .mixHash(Hash.ZERO)
            .nonce(0L)
            .blockHashFunction(MainnetBlockHashFunction::createHash)
            .buildBlockHeader();
    final BlockBody body = new BlockBody(Collections.emptyList(), Collections.emptyList());
    return new Block(header, body);
  }

  private static class StubbedPeerConnection implements PeerConnection {
    private final BytesValue nodeId;

    StubbedPeerConnection(final BytesValue nodeId) {
      this.nodeId = nodeId;
    }

    @Override
    public void send(final Capability capability, final MessageData message)
        throws PeerNotConnected {}

    @Override
    public Set<Capability> getAgreedCapabilities() {
      return Collections.singleton(Capability.create("eth", 63));
    }

    @Override
    public PeerInfo getPeer() {
      return new PeerInfo(0, "999", emptyList(), 0, nodeId);
    }

    @Override
    public void terminateConnection(
        final DisconnectMessage.DisconnectReason reason, final boolean peerInitiated) {}

    @Override
    public void disconnect(final DisconnectMessage.DisconnectReason reason) {}

    @Override
    public SocketAddress getLocalAddress() {
      return null;
    }

    @Override
    public SocketAddress getRemoteAddress() {
      return null;
    }

    @Override
    public String toString() {
      return nodeId.toString();
    }
  }

  private static BytesValue createId(final int id) {
    return BytesValue.fromHexString(String.format("%0128x", id));
  }
}
