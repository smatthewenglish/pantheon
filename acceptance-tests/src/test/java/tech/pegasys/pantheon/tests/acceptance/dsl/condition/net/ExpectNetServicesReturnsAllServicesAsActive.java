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
package tech.pegasys.pantheon.tests.acceptance.dsl.condition.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.net.InetAddresses;
import tech.pegasys.pantheon.tests.acceptance.dsl.condition.Condition;
import tech.pegasys.pantheon.tests.acceptance.dsl.node.Node;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.net.NetServicesTransaction;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.net.NetTransactions;
import tech.pegasys.pantheon.util.NetworkUtility;
import tech.pegasys.pantheon.util.enode.EnodeURL;

import java.util.Map;
import java.util.regex.Pattern;

public class ExpectNetServicesReturnsAllServicesAsActive implements Condition {

  private final NetServicesTransaction transaction;

  public ExpectNetServicesReturnsAllServicesAsActive(final NetServicesTransaction transaction) {
    this.transaction = transaction;
  }

  @Override
  public void verify(final Node node) {
    final Map<String, Map<String, String>> result = node.execute(transaction);

    assertThat(InetAddresses.isUriInetAddress(result.get("p2p").get("host"))).isTrue();
    final int p2pPort = Integer.valueOf(result.get("p2p").get("port"));
    assertThat(NetworkUtility.isValidPort(p2pPort)).isTrue();

    assertThat(InetAddresses.isUriInetAddress(result.get("ws").get("host"))).isTrue();
    final int wsPort = Integer.valueOf(result.get("ws").get("port"));
    assertThat(NetworkUtility.isValidPort(wsPort) || wsPort == 0).isTrue();

    assertThat(InetAddresses.isUriInetAddress(result.get("jsonrpc").get("host"))).isTrue();
    final int jsonRpcPort = Integer.valueOf(result.get("jsonrpc").get("port"));
    assertThat(NetworkUtility.isValidPort(jsonRpcPort) || jsonRpcPort == 0).isTrue();
  }

  public static class ExpectNetServicesReturnsNoServicesAsActive implements Condition {

    private final NetTransactions transaction;

    public ExpectNetServicesReturnsNoServicesAsActive(final NetTransactions transaction) {
      this.transaction = transaction;
    }

    @Override
    public void verify(final Node node) {
      assertThat(node.execute(transaction.netServices())).isNull();
    }
  }
}
