package com.example.mmtv.api

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private var retrofit: Retrofit? = null
    private var lastBaseUrl: String? = null

    private fun getOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(180, TimeUnit.SECONDS) // Ökad rejält för tunga M3U-filer
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "VLC") // Identifiera oss som VLC
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    fun getClient(baseUrl: String): XCodesApi {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        // Cachea retrofit-instansen så vi inte skapar nya i onödan
        if (retrofit != null && lastBaseUrl == url) {
            return retrofit!!.create(XCodesApi::class.java)
        }

        lastBaseUrl = url
        retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(getOkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        return retrofit!!.create(XCodesApi::class.java)
    }
}
