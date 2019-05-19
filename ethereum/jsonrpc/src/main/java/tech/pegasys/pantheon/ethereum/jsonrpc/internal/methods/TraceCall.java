package tech.pegasys.pantheon.ethereum.jsonrpc.internal.methods;

import tech.pegasys.pantheon.ethereum.jsonrpc.RpcMethod;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.JsonRpcRequest;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.exception.InvalidJsonRpcParameters;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.BlockParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonCallParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.parameters.JsonRpcParameter;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.queries.BlockchainQueries;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcErrorResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcResponse;
import tech.pegasys.pantheon.ethereum.jsonrpc.internal.response.JsonRpcSuccessResponse;
import tech.pegasys.pantheon.ethereum.transaction.CallParameter;
import tech.pegasys.pantheon.ethereum.transaction.TransactionSimulator;

import java.util.OptionalLong;

import static tech.pegasys.pantheon.ethereum.jsonrpc.JsonRpcErrorConverter.convertTransactionInvalidReason;

public class TraceCall implements JsonRpcMethod  {

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
        // TODO: Update once we mine and better understand pending semantics.
        // This may also be worth always returning null for.
        return null;
    }

    protected Object latestResult(final JsonRpcRequest request) {
        return resultByBlockNumber(request, blockchainQueries.headBlockNumber());
    }

    protected Object findResultByParamType(final JsonRpcRequest request) {
        final BlockParameter blockParam = blockParameter(request);

        final Object result;
        final OptionalLong blockNumber = blockParam.getNumber();
        if (blockNumber.isPresent()) {
            result = resultByBlockNumber(request, blockNumber.getAsLong());
        } else if (blockParam.isLatest()) {
            result = latestResult(request);
        } else {
            // If block parameter is not numeric or latest, it is pending.
            result = pendingResult(request);
        }

        return result;
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
                .map(
                        result ->
                                result
                                        .getValidationResult()
                                        .either(
                                                (() ->
                                                        new JsonRpcSuccessResponse(
                                                                request.getId(), result.getOutput().toString())),
                                                reason ->
                                                        new JsonRpcErrorResponse(
                                                                request.getId(), convertTransactionInvalidReason(reason))))
                .orElse(validRequestBlockNotFound(request));
    }

    private JsonRpcSuccessResponse validRequestBlockNotFound(final JsonRpcRequest request) {
        return new JsonRpcSuccessResponse(request.getId(), null);
    }

    public JsonRpcResponse response(final JsonRpcRequest request) {
        return (JsonRpcResponse) findResultByParamType(request);
    }

    private CallParameter validateAndGetCallParams(final JsonRpcRequest request) {
        final JsonCallParameter callParams =
                parameters().required(request.getParams(), 0, JsonCallParameter.class);
        if (callParams.getTo() == null) {
            throw new InvalidJsonRpcParameters("Missing \"to\" field in call arguments");
        }
        return callParams;
    }
}
