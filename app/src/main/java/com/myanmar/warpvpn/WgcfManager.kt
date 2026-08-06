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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

class WgcfManager(private val onLogListener: ((String ) -> Unit)? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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
        val ipRanges = listOf(
            "162.159.192.",
            "162.159.193.",
            "162.159.195.",
            "162.159.204."
        )
        
        val ipList = mutableListOf<String>()
        for (range in ipRanges) {
            val randomStart = (1..220).random()
            for (i in randomStart until randomStart + 30) {
                ipList.add("$range$i")
            }
        }
        return ipList.shuffled()
    }

    suspend fun findFastestWorkingEndpoint(timeoutMs: Int = 2000): String = withContext(Dispatchers.IO) {
        log("🔍 Scanning multiple cloudflare ip ranges...")
        
        val allIps = generateWarpIpList()
        var bestIp: String? = null
        var lowestLatency = Long.MAX_VALUE
        
        val chunkedIps = allIps.chunked(50)
        
        for ((index, chunk) in chunkedIps.withIndex()) {
            log("📡 Scanning batch #${index + 1}...")
            
            val results = coroutineScope {
                chunk.map { ip ->
                    async {
                        val latency = testEndpointLatency(ip, timeoutMs)
                        if (latency > 0) Pair(ip, latency) else null
                    }
                }.awaitAll().filterNotNull()
            }

            if (results.isNotEmpty()) {
                val fastestInBatch = results.minByOrNull { it.second }
                if (fastestInBatch != null) {
                    lowestLatency = fastestInBatch.second
                    bestIp = fastestInBatch.first
                    log("✨ Found alive candidate: ${maskIp(fastestInBatch.first)} (${fastestInBatch.second}ms)")
                    break
                }
            }
        }

        val finalIp = bestIp ?: "162.159.193.1"
        if (bestIp != null) {
            log("🏆 Selected endpoint: ${maskIp(finalIp)} ($lowestLatency ms)")
        } else {
            log("⚠️ No alive response. Using default fallback.")
        }

        return@withContext finalIp
    }

    private fun testEndpointLatency(ip: String, timeoutMs: Int): Long {
        val port = 2408 
        return try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val latency = System.currentTimeMillis() - startTime
            socket.close()
            latency
        } catch (e: Exception) {
            -1L
        }
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

        log("✅ Cloudflare wireGuard config successfully generated!")

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
        log("🌐 Requesting wireGuard credentials from Backup API...")
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
            throw Exception("Backup API error: ${response.code} - ${response.message}")
        }

        val json = JSONObject(responseData)
        val success = json.optBoolean("success", false)

        if (!success) {
            val errorMsg = json.optString("error", "Unknown error")
            throw Exception("Backup API failed: $errorMsg")
        }

        val configObj = json.getJSONObject("config")
        val clientPrivateKey = configObj.getString("private_key").trim()
        val rawAddress = configObj.getString("address").trim()
        val serverPublicKey = configObj.getString("public_key").trim()

        log("✅ Backup API wireGuard config successfully generated!")

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
        return@withContext testEndpointLatency(endpoint, timeout) > 0
    }

    fun getAllEndpoints(): List<String> = generateWarpIpList()
}
