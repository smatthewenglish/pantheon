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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tech.pegasys.pantheon.ethereum.jsonrpc.internal.JsonRpcRequest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonCallParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcSuccessResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.results.Quantity;
import tech.pegasys.pantheon.ethereum.transaction.CallParameter;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulatorResult;
import tech.pegasys.pantheon.util.bytes.BytesValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@SuppressWarnings({"unchecked", "rawtypes"})
@RunWith(MockitoJUnitRunner.class)
public class TraceCallTest {

  private TraceCall method;

  @Mock private BlockchainQueries blockchainQueries;
  @Mock private TransactionSimulator transactionSimulator;

  @Before
  public void setUp() {
    method = new TraceCall(blockchainQueries, transactionSimulator, new JsonRpcParameter());
  }

  @Test
  public void shouldReturnCorrectMethodName() {
    assertThat(method.getName()).isEqualTo("trace_call");
  }

  @Test
  public void shouldThrowInvalidJsonRpcParametersExceptionWhenMissingToField() {
    final CallParameter callParameter = new JsonCallParameter("0x0", null, "0x0", "0x0", "0x0", "");
    final JsonRpcRequest request = traceCallRequest(callParameter, "latest");

    final Throwable thrown = catchThrowable(() -> method.response(request));

    assertThat(thrown)
        .isInstanceOf(InvalidJsonRpcParameters.class)
        .hasNoCause()
        .hasMessage("Missing \"to\" field in call arguments");
  }

  @Test
  public void shouldReturnNullWhenProcessorReturnsEmpty() {
    final JsonRpcRequest request = traceCallRequest(callParameter(), "latest");

    final Map<Object, Object> result = new HashMap<>();
    result.put("output", null);
    result.put("stateDiff", null);
    final JsonRpcResponse expectedResponse = new JsonRpcSuccessResponse(null, result);

    when(transactionSimulator.process(any(), anyLong())).thenReturn(Optional.empty());

    final JsonRpcResponse response = method.response(request);

    assertThat(response).isEqualToComparingFieldByField(expectedResponse);
    verify(transactionSimulator).process(any(), anyLong());
  }

  @Test
  public void shouldAcceptRequestWhenMissingOptionalFields() {
    final CallParameter callParameter = new JsonCallParameter(null, "0x0", null, null, null, null);
    final JsonRpcRequest request = traceCallRequest(callParameter, "latest");

    final Map<Object, Object> result = new HashMap<>();
    result.put("output", "0x");
    result.put("stateDiff", null);
    final JsonRpcResponse expectedResponse = new JsonRpcSuccessResponse(null, result);

    mockTransactionProcessorSuccessResult(BytesValue.of());

    final JsonRpcResponse response = method.response(request);

    assertThat(response).isEqualToComparingFieldByFieldRecursively(expectedResponse);
    verify(transactionSimulator).process(eq(callParameter), anyLong());
  }

  @Test
  public void shouldReturnExecutionResultWhenExecutionIsSuccessful() {
    final JsonRpcRequest request = traceCallRequest(callParameter(), "latest");
    mockTransactionProcessorSuccessResult(BytesValue.of(1));

    final Map<Object, Object> result = new HashMap<>();
    result.put("output", "0x01");
    result.put("stateDiff", null);

    final JsonRpcResponse expectedResponse = new JsonRpcSuccessResponse(null, result);
    final JsonRpcResponse response = method.response(request);

    assertThat(response).isEqualToComparingFieldByFieldRecursively(expectedResponse);
    verify(transactionSimulator).process(eq(callParameter()), anyLong());
  }

  @Test
  public void shouldUseCorrectBlockNumberWhenLatest() {
    final JsonRpcRequest request = traceCallRequest(callParameter(), "latest");
    when(blockchainQueries.headBlockNumber()).thenReturn(11L);
    when(transactionSimulator.process(any(), anyLong())).thenReturn(Optional.empty());

    method.response(request);

    verify(transactionSimulator).process(any(), eq(11L));
  }

  @Test
  public void shouldUseCorrectBlockNumberWhenEarliest() {
    final JsonRpcRequest request = traceCallRequest(callParameter(), "earliest");
    when(transactionSimulator.process(any(), anyLong())).thenReturn(Optional.empty());
    method.response(request);

    verify(transactionSimulator).process(any(), eq(0L));
  }

  @Test
  public void shouldUseCorrectBlockNumberWhenSpecified() {
    final JsonRpcRequest request = traceCallRequest(callParameter(), Quantity.create(13L));
    when(transactionSimulator.process(any(), anyLong())).thenReturn(Optional.empty());

    method.response(request);

    verify(transactionSimulator).process(any(), eq(13L));
  }

  private CallParameter callParameter() {
    return new JsonCallParameter("0x0", "0x0", "0x0", "0x0", "0x0", "");
  }

  private JsonRpcRequest traceCallRequest(
      final CallParameter callParameter, final String blockNumberInHex) {
    return new JsonRpcRequest("2.0", "trace_call", new Object[] {callParameter, blockNumberInHex});
  }

  private void mockTransactionProcessorSuccessResult(final BytesValue output) {
    final TransactionSimulatorResult result = mock(TransactionSimulatorResult.class);
    when(result.getOutput()).thenReturn(output);
    when(transactionSimulator.process(any(), anyLong())).thenReturn(Optional.of(result));
  }
}
