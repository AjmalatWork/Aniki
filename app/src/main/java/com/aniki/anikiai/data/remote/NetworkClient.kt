package com.aniki.anikiai.data.remote

import com.aniki.anikiai.auth.FirebaseAuthInterceptor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object NetworkConfig {
    // Dev only: server/ has no HTTPS in this slice, see network_security_config.xml.
    // Emulator: reaches the host's localhost via the 10.0.2.2 alias automatically.
    // Physical device: run `adb reverse tcp:4000 tcp:4000` so the device's own
    // 127.0.0.1:4000 tunnels to the host machine's server over the USB connection.
    const val BASE_URL = "http://127.0.0.1:4000/"
}

object NetworkClient {
    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(FirebaseAuthInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    val api: AnikiApi by lazy {
        Retrofit.Builder()
            .baseUrl(NetworkConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AnikiApi::class.java)
    }
}
