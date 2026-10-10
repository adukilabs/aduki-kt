package pro.aduki.net.grpc

import io.grpc.CallCredentials
import io.grpc.Metadata
import io.grpc.Status
import java.util.concurrent.Executor

/**
 * Credentials attaches API key authorization metadata to all gRPC service calls.
 */
class Credentials(private val key: String) : CallCredentials() {
    private val authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    fun apply(appExecutor: Executor, applier: MetadataApplier) {
        appExecutor.execute {
            try {
                val headers = Metadata()
                headers.put(authKey, "Key $key")
                applier.apply(headers)
            } catch (t: Throwable) {
                applier.fail(Status.UNAUTHENTICATED.withCause(t))
            }
        }
    }

    override fun applyRequestMetadata(
        requestInfo: RequestInfo?,
        appExecutor: Executor,
        applier: MetadataApplier
    ) {
        apply(appExecutor, applier)
    }

    override fun thisUsesUnstableApi() {}
}
