package com.example.mmtv.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SelectiveApiTest {
    @Test fun sendsCategoryAndChannelFiltersToServer() = runBlocking {
        val requests = mutableListOf<okhttp3.HttpUrl>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request.url)
            val body = if (request.url.queryParameter("action") == "get_simple_data_table") {
                """{"epg_listings":[{"title":"TnloZXRlcg==","description":"Beskrivning","start_timestamp":"100","stop_timestamp":"200"}]}"""
            } else "[]"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/")
            .client(client).addConverterFactory(GsonConverterFactory.create()).build().create(XCodesApi::class.java)
        api.getLiveStreams("user", "pass", categoryId = "se-tv")
        api.getMovies("user", "pass", categoryId = "se-film")
        api.getSeries("user", "pass", categoryId = "se-series")
        val epg = api.getChannelEpg("user", "pass", 42)
        assertEquals(listOf("se-tv", "se-film", "se-series"), requests.take(3).map { it.queryParameter("category_id") })
        assertEquals("42", requests.last().queryParameter("stream_id"))
        assertEquals("get_simple_data_table", requests.last().queryParameter("action"))
        assertEquals("Beskrivning", epg.listings!!.single().description)
        assertEquals(200L, epg.listings!!.single().stopTimestamp)
    }
}
