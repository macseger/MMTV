package com.example.mmtv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger

class MmtvApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Use 25% of available memory for images
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100 * 1024 * 1024) // 100MB disk cache
                    .build()
            }
            .crossfade(false) // Inaktivera crossfade på TV för snabbare rendering
            .allowHardware(true) // Säkerställ hårdvaruacceleration
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            // .logger(DebugLogger()) // Ta bort loggning i "produktionsliknande" testning för prestanda
            .build()
    }
}
