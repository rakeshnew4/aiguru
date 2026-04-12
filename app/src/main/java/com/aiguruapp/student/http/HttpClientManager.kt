package com.aiguruapp.student.http

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * Singleton HTTP client manager for connection pooling.
 * 
 * Centralizes OkHttpClient creation to enable:
 * - Connection pooling (reuse TCP connections across requests)
 * - Reduced handshake latency (TLS negotiation cost)
 * - Controlled resource usage (memory, file descriptors)
 * - Consistent timeout configuration
 */
object HttpClientManager {

    private lateinit var _standardClient: OkHttpClient
    private lateinit var _ncertClient: OkHttpClient
    private lateinit var _longTimeoutClient: OkHttpClient

    /**
     * Standard client for API calls (30s connect, 60s read/write).
     * ~50 connections in pool, 5 min idle timeout.
     */
    val standardClient: OkHttpClient
        get() {
            if (!::_standardClient.isInitialized) {
                _standardClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .connectionPool(ConnectionPool(50, 5, TimeUnit.MINUTES))
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build()
            }
            return _standardClient
        }

    /**
     * NCERT client for browser-like downloads (TLS 1.2/1.3 fallback).
     * ncert.nic.in sometimes rejects default TLS config.
     */
    val ncertClient: OkHttpClient
        get() {
            if (!::_ncertClient.isInitialized) {
                val tlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build()
                _ncertClient = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
                    .connectionPool(ConnectionPool(30, 5, TimeUnit.MINUTES))
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build()
            }
            return _ncertClient
        }

    /**
     * Long-timeout client for LLM streaming (120s read, waits for full response).
     */
    val longTimeoutClient: OkHttpClient
        get() {
            if (!::_longTimeoutClient.isInitialized) {
                _longTimeoutClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build()
            }
            return _longTimeoutClient
        }
}
