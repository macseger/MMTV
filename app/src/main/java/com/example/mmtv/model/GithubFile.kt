package com.example.mmtv.model

import com.google.gson.annotations.SerializedName

data class GithubFile(
    val name: String,
    @SerializedName("download_url") val downloadUrl: String?
)
