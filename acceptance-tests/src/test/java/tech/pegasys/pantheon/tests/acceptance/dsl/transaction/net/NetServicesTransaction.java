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
package tech.pegasys.pantheon.tests.acceptance.dsl.transaction.net;

import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.JsonRequestFactories;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.NetServicesJsonRpcRequestFactory;
import tech.pegasys.pantheon.tests.acceptance.dsl.transaction.Transaction;

import java.util.Map;

import org.web3j.protocol.core.Request;

public class NetServicesTransaction implements Transaction<Map<String, Map<String, String>>> {

  NetServicesTransaction() {}

  @Override
  public Map<String, Map<String, String>> execute(final JsonRequestFactories requestFactories) {
    NetServicesJsonRpcRequestFactory.NetServicesResponse netServicesResponse = null;
    try {
      NetServicesJsonRpcRequestFactory netServicesJsonRpcRequestFactory =
          requestFactories.netServices();
      Request<?, NetServicesJsonRpcRequestFactory.NetServicesResponse> request =
          netServicesJsonRpcRequestFactory.netServices();
      netServicesResponse = request.send();
    } catch (final Exception ignored) {
    }
    return (netServicesResponse != null ? netServicesResponse.getResult() : null) != null
        ? netServicesResponse.getResult()
        : null;
  }
}
