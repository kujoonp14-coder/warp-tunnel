package com.myanmar.warpvpn

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class WgcfManager(private val onLogListener: ((String) -> Unit)? = null) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cfApiBases: List<String>
        get() = listOf(
            NativeUtils.getCfApiBase1(),
            NativeUtils.getCfApiBase2(),
            NativeUtils.getCfApiBase3()
        )

    private val customApiUrl: String
        get() = NativeUtils.getCustomApiUrl()

    private fun log(message: String) {
        onLogListener?.invoke(message)
    }

    private fun maskIp(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.***.***"
        } else {
            "***.***.***.***"
        }
    }

    private fun generateWarpIpList(): List<String> {
        val ipv4Prefixes = listOf(
            "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.",
            "162.159.192.", "162.159.193.", "162.159.195.", "8.34.146.",
            "8.39.214.", "8.39.204.", "8.6.112.", "8.35.211.", "8.39.125.",
            "8.47.69."
        )

        val ipList = mutableListOf<String>()
        for (prefix in ipv4Prefixes) {
            for (i in 1..254) {
                ipList.add("$prefix$i")
            }
        }
        return ipList.shuffled()
    }
    
    suspend fun checkNetworkStats(): Pair<Int, Float> = withContext(Dispatchers.IO) {
        log("📊 Determining network quality & jitter...")
        val testTargetUrl = "https://www.google.com/generate_204"
        val testCount = 10
        val successfulLatencies = mutableListOf<Long>()

        for (i in 1..testCount) {
            try {
                val start = System.currentTimeMillis()
                val request = Request.Builder().url(testTargetUrl).head().build()
                val response = client.newCall(request).execute()
                val latency = System.currentTimeMillis() - start

                if (response.code == 204 || response.isSuccessful) {
                    successfulLatencies.add(latency)
                }
                response.close()
            } catch (e: Exception) {
                // Connection fail
            }
        }

        if (successfulLatencies.isEmpty()) {
            log("⚠️ Network test failed. Using default settings.")
            return@withContext Pair(3, 100f)
        }

        successfulLatencies.sort()
        val medianLatency = successfulLatencies[successfulLatencies.size / 2].toInt()
        val lossRate = ((testCount - successfulLatencies.size).toFloat() / testCount) * 100

        var avgJitter = 0f
        if (successfulLatencies.size > 1) {
            var totalJitter = 0L
            for (i in 0 until successfulLatencies.size - 1) {
                totalJitter += abs(successfulLatencies[i + 1] - successfulLatencies[i])
            }
            avgJitter = totalJitter.toFloat() / (successfulLatencies.size - 1)
        }

        log("📶 Network Stats -> Median Latency: ${medianLatency}ms | Jitter: ${String.format("%.1f", avgJitter)}ms | Loss: ${String.format("%.1f", lossRate)}%")

        val retries = when {
            medianLatency >= 200 || lossRate >= 10.0f || avgJitter >= 10.0f -> 7
            medianLatency >= 100 || lossRate >= 5.0f || avgJitter >= 5.0f -> 5
            else -> 3
        }

        return@withContext Pair(retries, lossRate)
    }
    
    suspend fun findFastestWorkingEndpoint(timeoutMs: Int = 1200): String = withContext(Dispatchers.IO) {
        val (retries, _) = checkNetworkStats()

        log("🔍 Generated WARP IP targets for scanning...")
        log("⚡ Starting parallel concurrent scan (Retries per IP: $retries)...")

        val allIps = generateWarpIpList()
        var bestIp: String? = null
        var lowestLatency = Long.MAX_VALUE

        val chunkedIps = allIps.chunked(40)
        var batchIndex = 1

        for (chunk in chunkedIps) {
            log("📡 Scanning batch #$batchIndex (${chunk.size} IPs)...")

            val results = coroutineScope {
                chunk.mapIndexed { idx, ip ->
                    async {
                        delay((idx * 10).toLong())
                        val latency = testEndpointMultiRetry(ip, retries, timeoutMs)
                        if (latency > 0) Pair(ip, latency) else null
                    }
                }.awaitAll().filterNotNull()
            }

            if (results.isNotEmpty()) {
                val fastestInBatch = results.minByOrNull { it.second }
                if (fastestInBatch != null && fastestInBatch.second < lowestLatency) {
                    lowestLatency = fastestInBatch.second
                    bestIp = fastestInBatch.first
                    log("✨ Found alive candidate: ${maskIp(fastestInBatch.first)} (${fastestInBatch.second}ms)")
                    break
                }
            }
            batchIndex++
        }

        val finalIp = bestIp ?: "162.159.193.1"
        if (bestIp != null) {
            log("🏆 Selected fastest endpoint: ${maskIp(finalIp)} with Avg Latency $lowestLatency ms")
        } else {
            log("⚠️ Scan completed with no alive response. Using fallback: ${maskIp(finalIp)}")
        }

        return@withContext finalIp
    }
    
    private suspend fun testEndpointMultiRetry(ip: String, retries: Int, timeoutMs: Int): Long = coroutineScope {
        val latencies = mutableListOf<Long>()

        val jobs = (0 until retries).map { retryIndex ->
            async {
                delay((retryIndex * 50).toLong())
                testEndpointSingle(ip, timeoutMs)
            }
        }

        val results = jobs.awaitAll()
        for (l in results) {
            if (l > 0) latencies.add(l)
        }

        if (latencies.isEmpty()) return@coroutineScope -1L
        return@coroutineScope latencies.average().toLong()
    }

    private fun testEndpointSingle(ip: String, timeoutMs: Int): Long {
        try {
            val startTime = System.currentTimeMillis()
            val address = InetAddress.getByName(ip)
            if (address.isReachable(timeoutMs)) {
                return System.currentTimeMillis() - startTime
            }
        } catch (e: Exception) {
            // Ignore
        }
        return -1L
    }

    suspend fun registerAndGetConfig(
        engineMode: String = "CF_DIRECT",
        maxRetries: Int = 3
    ): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        val bestEndpoint = findFastestWorkingEndpoint()

        repeat(maxRetries) { attempt ->
            try {
                return@withContext if (engineMode == "CUSTOM_API") {
                    fetchFromCustomApi(bestEndpoint)
                } else {
                    fetchFromCloudflareApiWithFallback(bestEndpoint)
                }
            } catch (e: Exception) {
                lastException = e
                log("❌ Attempt ${attempt + 1} failed: ${e.localizedMessage}")
                if (attempt < maxRetries - 1) {
                    delay(2000L * (attempt + 1))
                }
            }
        }

        throw lastException ?: Exception("All retry attempts failed")
    }

    private fun fetchFromCloudflareApiWithFallback(bestEndpoint: String): String {
        var lastException: Exception? = null

        for (apiBase in cfApiBases) {
            try {
                return fetchFromCloudflareApi(apiBase, bestEndpoint)
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: Exception("All cloudflare api endpoints failed")
    }

    private fun fetchFromCloudflareApi(apiBase: String, endpoint: String): String {
        log("🌐 Requesting wireGuard credentials from cloudflare api...")
        val keyPair = KeyPair()
        val privateKey = keyPair.privateKey.toBase64()
        val publicKey = keyPair.publicKey.toBase64()

        val installId = UUID.randomUUID().toString()

        val regJson = JSONObject().apply {
            put("key", publicKey)
            put("install_id", installId)
            put("fcm_token", "")
            put("tos", "2024-01-01T00:00:00.000Z")
            put("model", "Android")
            put("type", "Android")
            put("locale", "en_US")
        }

        val regRequest = Request.Builder()
            .url("$apiBase/reg")
            .header("User-Agent", "okhttp/3.12.1")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("Accept", "application/json")
            .post(regJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(regRequest).execute()
        val responseData = response.body?.string() ?: throw Exception("Empty response from Cloudflare API")

        if (!response.isSuccessful) {
            throw Exception("Cloudflare api error: ${response.code} - ${response.message}")
        }

        val rootJson = JSONObject(responseData)
        val result = if (rootJson.has("result") && !rootJson.isNull("result")) {
            rootJson.getJSONObject("result")
        } else {
            rootJson
        }

        val config = result.getJSONObject("config")
        val peers = config.getJSONArray("peers").getJSONObject(0)
        val serverPublicKey = peers.getString("public_key")

        val interfaceObj = config.getJSONObject("interface")
        val addresses = interfaceObj.getJSONObject("addresses")
        val ipv4 = addresses.getString("v4")
        val ipv6 = addresses.getString("v6")

        log("✅ Cloudflare wireGuard Config successfully generated!")

        return buildRawWireGuardConfig(
            privateKey = privateKey,
            endpoint = endpoint,
            port = "500",
            address = "$ipv4/32, $ipv6/128",
            publicKey = serverPublicKey,
            dns = "1.1.1.1, 1.0.0.1"
        )
    }

    private fun fetchFromCustomApi(bestEndpoint: String): String {
        log("🌐 Requesting wireGuard credentials from backup api...")
        val userId = (100000..999999).random().toString()
        val requestUrl = "$customApiUrl?user_id=$userId"

        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "okhttp/3.12.1")
            .header("Accept", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseData = response.body?.string() ?: throw Exception("Empty response from backup API")

        if (!response.isSuccessful) {
            throw Exception("Backup api error: ${response.code} - ${response.message}")
        }

        val json = JSONObject(responseData)
        val success = json.optBoolean("success", false)

        if (!success) {
            val errorMsg = json.optString("error", "Unknown error")
            throw Exception("Backup api failed: $errorMsg")
        }

        val configObj = json.getJSONObject("config")
        val clientPrivateKey = configObj.getString("private_key").trim()
        val rawAddress = configObj.getString("address").trim()
        val serverPublicKey = configObj.getString("public_key").trim()

        log("✅ Backup api wireGuard config successfully generated!")

        return buildRawWireGuardConfig(
            privateKey = clientPrivateKey,
            endpoint = bestEndpoint,
            port = "500",
            address = rawAddress,
            publicKey = serverPublicKey,
            dns = "1.1.1.1, 1.0.0.1"
        )
    }

    private fun buildRawWireGuardConfig(
        privateKey: String,
        endpoint: String,
        port: String,
        address: String,
        publicKey: String,
        dns: String
    ): String {
        val formattedAddress = if (address.contains(",") && !address.contains(", ")) {
            address.replace(",", ", ")
        } else {
            address
        }

        return """
            [Interface]
            PrivateKey = $privateKey
            Address = $formattedAddress
            DNS = $dns
            MTU = 1280
            
            [Peer]
            PublicKey = $publicKey
            Endpoint = $endpoint:$port
            AllowedIPs = 0.0.0.0/0, ::/0
        """.trimIndent()
    }

    suspend fun testEndpoint(endpoint: String, timeout: Int = 2000): Boolean = withContext(Dispatchers.IO) {
        return@withContext testEndpointSingle(endpoint, timeout) > 0
    }

    fun getAllEndpoints(): List<String> = generateWarpIpList()
}
