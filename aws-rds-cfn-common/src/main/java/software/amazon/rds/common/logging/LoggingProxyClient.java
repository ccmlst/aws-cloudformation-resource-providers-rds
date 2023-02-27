package software.amazon.rds.common.logging;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.cloudformation.proxy.ProxyClient;

@RequiredArgsConstructor
public class LoggingProxyClient<ClientT> implements ProxyClient<ClientT> {

    final private RequestLogger requestLogger;
    final private ProxyClient<ClientT> proxyClient;

    @Override
    public <RequestT extends AwsRequest, ResponseT extends AwsResponse> ResponseT injectCredentialsAndInvokeV2(
            final RequestT request,
            final Function<RequestT, ResponseT> requestFunction
    ) {
        return logRequestResponseAndDelegate(request, requestFunction, proxyClient::injectCredentialsAndInvokeV2);
    }

    @Override
    public <RequestT extends AwsRequest, ResponseT extends AwsResponse> CompletableFuture<ResponseT> injectCredentialsAndInvokeV2Async(
            final RequestT request,
            final Function<RequestT, CompletableFuture<ResponseT>> requestFunction
    ) {
        return logRequestAndDelegate(request, requestFunction, proxyClient::injectCredentialsAndInvokeV2Async);
    }

    @Override
    public <RequestT extends AwsRequest, ResponseT extends AwsResponse, IterableT extends SdkIterable<ResponseT>> IterableT injectCredentialsAndInvokeIterableV2(
            final RequestT request,
            final Function<RequestT, IterableT> requestFunction
    ) {
        return logRequestAndDelegate(request, requestFunction, proxyClient::injectCredentialsAndInvokeIterableV2);
    }

    @Override
    public <RequestT extends AwsRequest, ResponseT extends AwsResponse> ResponseInputStream<ResponseT> injectCredentialsAndInvokeV2InputStream(
            final RequestT request,
            final Function<RequestT, ResponseInputStream<ResponseT>> requestFunction
    ) {
        return logRequestAndDelegate(request, requestFunction, proxyClient::injectCredentialsAndInvokeV2InputStream);
    }

    @Override
    public <RequestT extends AwsRequest, ResponseT extends AwsResponse> ResponseBytes<ResponseT> injectCredentialsAndInvokeV2Bytes(
            final RequestT request,
            final Function<RequestT, ResponseBytes<ResponseT>> requestFunction
    ) {
        return logRequestAndDelegate(request, requestFunction, proxyClient::injectCredentialsAndInvokeV2Bytes);
    }

    @Override
    public ClientT client() {
        return proxyClient.client();
    }

    private <RequestT extends AwsRequest, ResultT> ResultT logRequestResponseAndDelegate(
            final RequestT request,
            final Function<RequestT, ResultT> requestFunction,
            final BiFunction<RequestT, Function<RequestT, ResultT>, ResultT> injectCredentials
    ) {
        ResultT result = null;
        try {
            requestLogger.log(request);
            result = injectCredentials.apply(request, requestFunction);
        } catch (Exception e) {
            requestLogger.logAndThrow(e);
        }
        requestLogger.log(result);
        return result;
    }

    private <RequestT extends AwsRequest, ResultT> ResultT logRequestAndDelegate(
            final RequestT request,
            final Function<RequestT, ResultT> requestFunction,
            final BiFunction<RequestT, Function<RequestT, ResultT>, ResultT> injectCredentials
    ) {
        ResultT result = null;
        try {
            requestLogger.log(request);
            result = injectCredentials.apply(request, requestFunction);
        } catch (Exception e) {
            requestLogger.logAndThrow(e);
        }
        requestLogger.log("[Result log omitted]");
        return result;
    }
}
