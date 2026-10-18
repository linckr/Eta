package io.github.mangi.eta.agent.model

import okhttp3.Response

/** Trust requires actual end-of-body, not a parseable prefix. Bounds decoded text to the limit and probes one additional byte for EOF. */
internal data class BoundedHttpErrorBody(val text: String, val complete: Boolean) {
    companion object {
        const val MAX_BYTES = 16_384L
        fun read(response: Response): BoundedHttpErrorBody {
            val body = response.body
            val source = body.source()
            val hasExtra = source.request(MAX_BYTES + 1)
            val bytes = source.readByteArray(minOf(source.buffer.size, MAX_BYTES))
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            return BoundedHttpErrorBody(String(bytes, charset), complete = !hasExtra)
        }
    }
}
