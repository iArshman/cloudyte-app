package com.example
 
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import coil.Coil
import coil.ImageLoader
import com.example.api.ApiClient
import com.example.ui.AppNavigation
import com.example.ui.theme.BackgroundDark
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    ApiClient.init(this)

    // Configure Coil global ImageLoader with authenticated OkHttpClient
    val imageLoader = ImageLoader.Builder(this)
      .okHttpClient(ApiClient.okHttpClient)
      .crossfade(true)
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

