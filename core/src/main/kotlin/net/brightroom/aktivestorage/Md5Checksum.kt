package net.brightroom.aktivestorage

import java.security.MessageDigest

/** MD5 を [MessageDigest] でラップした [Checksum] のデフォルト実装（JVM）。 */
public class Md5Checksum : Checksum {
    override fun newHasher(): Hasher =
        object : Hasher {
            private val md = MessageDigest.getInstance("MD5")

            override fun update(
                source: ByteArray,
                startIndex: Int,
                endIndex: Int,
            ) {
                md.update(source, startIndex, endIndex - startIndex)
            }

            override fun digest(): ByteArray = md.digest()
        }
}
