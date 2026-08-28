package com.example
 
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.api.ApiClient
import com.example.sync.AutoSyncManager
import com.example.ui.AppNavigation
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ApiClient.init(this)
    
    AutoSyncManager.init(this, lifecycleScope)

    // Configure Coil global ImageLoader with memory and disk cache for high-speed previews
    val imageLoader = ImageLoader.Builder(this)
      .okHttpClient(ApiClient.okHttpClient)
      .crossfade(true)
      .memoryCache {
        MemoryCache.Builder(this)
          .maxSizePercent(0.25)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("image_preview_cache"))
          .maxSizeBytes(128L * 1024 * 1024) // 128 MB disk cache
          .build()
      }
      .build()
    Coil.setImageLoader(imageLoader)

    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = BackgroundDark) {
          AppNavigation()
        }
      }
    }
  }
}


