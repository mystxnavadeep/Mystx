package com.mystx.app.provider

import java.net.URI

/**
 * Endpoint validation shared by the Settings screen and the OpenAI-compatible
 * client. https:// is permitted for any valid host; cleartext http:// is
 * permitted only for loopback (localhost, 127.0.0.0/8, ::1) and RFC 1918
 * private IPv4 ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16).
 *
 * Android's network security config cannot express IP ranges, so the platform
 * layer allows cleartext while this guard enforces the private-LAN rule at the
 * app level.
 */
object EndpointValidator {

    enum class Error { NONE, INVALID }

    /**
     * Validates [endpoint]: `Error.NONE` if it is a usable https:// URL or an
     * http:// URL pointing at a loopback or RFC 1918 private host; `Error.INVALID`
     * otherwise (including blank, schemeless, malformed, or containing userInfo,
     * query, fragment, whitespace, or control characters).
     */
    fun validate(endpoint: String): Error {
        val trimmed = endpoint.trim()
        if (trimmed.isEmpty()) return Error.INVALID
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            return Error.INVALID
        }
        val uri = try { URI(trimmed) } catch (_: Exception) { return Error.INVALID }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return Error.INVALID
        val host = uri.host
        if (host.isNullOrBlank()) return Error.INVALID
        if (uri.userInfo != null || uri.rawUserInfo != null) return Error.INVALID
        if (uri.query != null || uri.rawQuery != null) return Error.INVALID
        if (uri.fragment != null || uri.rawFragment != null) return Error.INVALID
        if (scheme == "http" && !isPrivateHost(host)) return Error.INVALID
        return Error.NONE
    }

    /** Whether [host] is a loopback or RFC 1918 private IP address. */
    fun isPrivateHost(host: String): Boolean {
        // URI.getHost() returns IPv6 literals with brackets ([::1]) — strip them.
        val h = host.trim().lowercase().removeSurrounding("[", "]").removeSuffix(".")
        if (h == "localhost" || h == "::1") return true
        val ip = h.toIPv4OrNull() ?: return false
        if (ip[0] == 127) return true // loopback 127.0.0.0/8
        if (ip[0] == 10) return true // RFC1918 10.0.0.0/8
        if (ip[0] == 172 && ip[1] in 16..31) return true // RFC1918 172.16.0.0/12
        if (ip[0] == 192 && ip[1] == 168) return true // RFC1918 192.168.0.0/16
        return false
    }

    private fun String.toIPv4OrNull(): IntArray? {
        val parts = split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in parts.indices) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }
}
