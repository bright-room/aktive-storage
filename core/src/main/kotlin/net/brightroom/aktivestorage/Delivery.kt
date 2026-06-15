package net.brightroom.aktivestorage

import kotlinx.io.RawSource

/** 配信方法。Web 層がこれを 302 / stream に振り分ける。 */
public sealed interface Delivery {
    public data class Redirect(
        public val url: PresignedUrl,
    ) : Delivery

    public data class Proxy(
        public val blob: Blob,
        public val stream: RawSource,
    ) : Delivery
}
