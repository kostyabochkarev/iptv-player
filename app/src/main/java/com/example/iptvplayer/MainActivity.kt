package com.example.iptvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.inject.Inject

// --- Модель данных для канала ---
data class Channel(val name: String, val url: String)

// --- ViewModel ---
@HiltViewModel
class PlayerViewModel @Inject constructor(val playerManager: IptvPlayerManager) : androidx.lifecycle.ViewModel() {
    
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Функция загрузки и парсинга плейлиста
    fun loadPlaylist(url: String) {
        if (url.isBlank()) return
        isLoading = true
        errorMessage = null

        // Используем viewModelScope для фоновой загрузки
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val parsedChannels = mutableListOf<Channel>()
                val connection = URL(url).openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                val stream = connection.getInputStream()
                val reader = stream.bufferedReader()
                
                var currentName = ""

                // Читаем файл строка за строкой
                reader.forEachLine { line ->
                    if (line.startsWith("#EXTINF:")) {
                        // Вытаскиваем имя канала (после последней запятой)
                        val nameMatch = Regex(",(.*)$").find(line)
                        currentName = nameMatch?.groupValues?.get(1)?.trim() ?: "Без названия"
                    } else if (line.startsWith("http")) {
                        // Если строка начинается с http, это ссылка на поток
                        parsedChannels.add(Channel(currentName, line.trim()))
                    }
                }
                
                withContext(Dispatchers.Main) {
                    channels = parsedChannels
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Ошибка: ${e.message}"
                    isLoading = false
                }
            }
        }
    }
}

// --- Главный экран ---
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: PlayerViewModel = hiltViewModel()
                MainScreen(viewModel)
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: PlayerViewModel) {
    var inputUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        
        // Поле ввода ссылки
        OutlinedTextField(
            value = inputUrl,
            onValueChange = { inputUrl = it },
            label = { Text("Ссылка на M3U плейлист") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Кнопка загрузки
        Button(
            onClick = { viewModel.loadPlaylist(inputUrl) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.isLoading
        ) {
            if (viewModel.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text("Загрузить каналы")
            }
        }

        if (viewModel.errorMessage != null) {
            Text(
                text = viewModel.errorMessage!!,
                color = Color.Red,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Сетка каналов
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(viewModel.channels) { channel ->
                ChannelCard(channel) {
                    // ВОТ ЗДЕСЬ МЫ ЗАПУСКАЕМ ВИДЕО
                    // Если у вас в PlayerManager метод называется иначе, поменяйте эту строку
                    viewModel.playerManager.playChannel(channel.url) 
                }
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = channel.name, 
                color = Color.White, 
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}
