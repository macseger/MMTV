package com.example.mmtv.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MovieInfoResponseParsingTest {
    private val gson = Gson()

    @Test
    fun movieInfoObject_parsesNormally() {
        val response = gson.fromJson(
            """
            {
              "info": {
                "plot": "Server plot",
                "genre": "Drama",
                "rating": "7.5",
                "releasedate": "2024-01-02"
              },
              "movie_data": {
                "stream_id": 17,
                "name": "Example Movie"
              }
            }
            """.trimIndent(),
            MovieInfoResponse::class.java
        )

        assertNotNull(response.info)
        assertEquals("Server plot", response.info?.plot)
        assertEquals("2024-01-02", response.info?.releaseDate)
        assertEquals("Example Movie", response.movieData?.name)
    }

    @Test
    fun emptyMovieInfoArray_parsesAsNullAndPreservesRemainder() {
        val response = gson.fromJson(
            """
            {
              "info": [],
              "movie_data": {
                "stream_id": 17,
                "name": "Example Movie"
              }
            }
            """.trimIndent(),
            MovieInfoResponse::class.java
        )

        assertNull(response.info)
        assertEquals(17, response.movieData?.streamId)
        assertEquals("Example Movie", response.movieData?.name)
    }
}
