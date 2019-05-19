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
package tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods;

import tech.pegasys.pantheon.ethereum.jsonrpc.RpcMethod;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.JsonRpcRequest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.BlockParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonCallParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcSuccessResponse;
import tech.pegasys.pantheon.ethereum.transaction.CallParameter;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

@SuppressWarnings({"unchecked", "rawtypes"})
public class TraceCall implements JsonRpcMethod {

  private final TransactionSimulator transactionSimulator;
  private final BlockchainQueries blockchainQueries;
  private final JsonRpcParameter parameters;

  public TraceCall(
      final BlockchainQueries blockchainQueries,
      final TransactionSimulator transactionSimulator,
      final JsonRpcParameter parameters) {
    this.blockchainQueries = blockchainQueries;
    this.parameters = parameters;
    this.transactionSimulator = transactionSimulator;
  }

  protected BlockchainQueries blockchainQueries() {
    return blockchainQueries;
  }

  protected JsonRpcParameter parameters() {
    return parameters;
  }

  protected Object pendingResult(final JsonRpcRequest request) {
    return null;
  }

  protected Object latestResult(final JsonRpcRequest request) {
    return resultByBlockNumber(request, blockchainQueries.headBlockNumber());
  }

  @Override
  public String getName() {
    return RpcMethod.TRACE_CALL.getMethodName();
  }

  protected BlockParameter blockParameter(final JsonRpcRequest request) {
    return parameters().required(request.getParams(), 1, BlockParameter.class);
  }

  protected Object resultByBlockNumber(final JsonRpcRequest request, final long blockNumber) {
    final CallParameter callParams = validateAndGetCallParams(request);

    return transactionSimulator
        .process(callParams, blockNumber)
        .map(result -> result.getOutput().toString())
        .orElse(null);
  }

  private CallParameter validateAndGetCallParams(final JsonRpcRequest request) {
    final JsonCallParameter callParams =
        parameters().required(request.getParams(), 0, JsonCallParameter.class);
    if (callParams.getTo() == null) {
      throw new InvalidJsonRpcParameters("Missing \"to\" field in call arguments");
    }
    return callParams;
  }

  @Override
  public JsonRpcResponse response(final JsonRpcRequest request) {

    final BlockParameter blockParam = blockParameter(request);

    final OptionalLong blockNumber = blockParam.getNumber();

    final Map result = new HashMap<>();

    if (blockNumber.isPresent()) {
      result.put("output", resultByBlockNumber(request, blockNumber.getAsLong()));
    } else if (blockParam.isLatest()) {
      result.put("output", latestResult(request));
    } else {
      // If block parameter is not numeric or latest, it is pending.
      result.put("output", pendingResult(request));
    }

    result.put("stateDiff", null);

    return new JsonRpcSuccessResponse(request.getId(), result);
  }
}
