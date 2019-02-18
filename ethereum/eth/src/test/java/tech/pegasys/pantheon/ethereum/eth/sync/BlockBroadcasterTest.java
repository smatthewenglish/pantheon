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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import java.util.stream.Stream;

import org.junit.Test;

public class BlockBroadcasterTest {

  @Test
  public void blockPropagationUnitTest() {
    final EthPeer ethPeer = mock(EthPeer.class);
    final EthPeers ethPeers = mock(EthPeers.class);
    when(ethPeers.availablePeers()).thenReturn(Stream.of(ethPeer));

    final EthContext ethContext = mock(EthContext.class);
    when(ethContext.getEthPeers()).thenReturn(ethPeers);

    final BlockBroadcaster blockBroadcaster = new BlockBroadcaster(ethContext);
    final Block block = generateBlock();
    blockBroadcaster.propagate(block, UInt256.ZERO);

    verify(ethPeer, times(1)).propagateBlock(any(), any());
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
}
