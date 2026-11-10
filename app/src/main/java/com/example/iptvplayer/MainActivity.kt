package com.example.iptvplayer

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import androidx.room.*
import coil.compose.AsyncImage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class MyApp : Application()

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6CDBAC), onPrimary = Color(0xFF003829),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1D24), onSurface = Color(0xFFE2E2E6)
)

data class Channel(
    val id: String, val name: String, val tvgId: String?, val tvgName: String?,
    val logoUrl: String?, val streamUrl: String, val categoryName: String,
    val userAgent: String?, val isFavorite: Boolean
)

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String, val name: String, val tvgId: String?,
    val tvgName: String?, val logoUrl: String?, val streamUrl: String,
    val categoryName: String, val userAgent: String?, val isFavorite: Boolean = false
) {
    fun toDomain() = Channel(id, name, tvgId, tvgName, logoUrl, streamUrl, categoryName, userAgent, isFavorite)
}

fun Channel.toEntity() = ChannelEntity(id, name, tvgId, tvgName, logoUrl, streamUrl, categoryName, userAgent, isFavorite)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun getAllChannels(): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE categoryName = :categoryName ORDER BY name ASC")
    fun getChannelsByCategory(categoryName: String): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    fun searchChannels(query: String): Flow<List<ChannelEntity>>
    @Query("UPDATE channels SET isFavorite = NOT isFavorite WHERE id = :channelId")
    suspend fun toggleFavorite(channelId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)
}

@Database(entities = [ChannelEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
}

class M3uParser {
    fun parse(inputStream: java.io.InputStream, chunkSize: Int = 100): Flow<List<Channel>> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream))
        var pendingExtinf: String? = null
        val chunk = ArrayList<Channel>(chunkSize)
        val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
        val nameRegex = Regex(""",([^,]+)$""")

        reader.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    pendingExtinf = trimmed
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && pendingExtinf != null) {
                    val attrs = attrRegex.findAll(pendingExtinf!!).associate { it.groupValues[1] to it.groupValues[2] }
                    val name = nameRegex.find(pendingExtinf!!)?.groupValues?.get(1)?.trim() ?: attrs["tvg-name"] ?: "Unknown"
                    val category = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Разное"
                    chunk.add(Channel(UUID.nameUUIDFromBytes(trimmed.toByteArray()).toString(), name, attrs["tvg-id"], attrs["tvg-name"], attrs["tvg-logo"], trimmed, category, attrs["http-user-agent"], false))
                    pendingExtinf = null
                    if (chunk.size >= chunkSize) { emit(chunk.toList()); chunk.clear() }
                }
            }
        }
        if (chunk.isNotEmpty()) emit(chunk.toList())
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)
}

interface ChannelRepository {
    fun getAllChannels(): Flow<List<Channel>>
    fun getChannelsByCategory(categoryName: String): Flow<List<Channel>>
    fun searchChannels(query: String): Flow<List<Channel>>
    suspend fun toggleFavorite(channelId: String)
    suspend fun saveChannels(channels: List<Channel>)
}

class ChannelRepositoryImpl @Inject constructor(private val dao: ChannelDao) : ChannelRepository {
    override fun getAllChannels() = dao.getAllChannels().map { list -> list.map { it.toDomain() } }
    override fun getChannelsByCategory(categoryName: String) = dao.getChannelsByCategory(categoryName).map { list -> list.map { it.toDomain() } }
    override fun searchChannels(query: String) = dao.searchChannels(query).map { list -> list.map { it.toDomain() } }
    override suspend fun toggleFavorite(channelId: String) = dao.toggleFavorite(channelId)
    override suspend fun saveChannels(channels: List<Channel>) = dao.insertChannels(channels.map { it.toEntity() })
}

class GetChannelsUseCase @Inject constructor(private val repository: ChannelRepository) {
    operator fun invoke(category: String? = null, query: String? = null): Flow<List<Channel>> {
        return when {
            !query.isNullOrBlank() -> repository.searchChannels(query)
            !category.isNullOrBlank() -> repository.getChannelsByCategory(category)
            else -> repository.getAllChannels()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "iptv_db").fallbackToDestructiveMigration().build()
    
    @Provides @Singleton
    fun provideChannelDao(db: AppDatabase) = db.channelDao()

    @Provides @Singleton
    fun provideChannelRepository(dao: ChannelDao): ChannelRepository = ChannelRepositoryImpl(dao)
}

data class MainUiState(
    val channels: List<Channel> = emptyList(),
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    val searchQuery: String = ""
)

@OptIn(FlowPreview::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val getChannelsUseCase: GetChannelsUseCase,
    private val repository: ChannelRepository
) : ViewModel() {
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")

    val uiState: StateFlow<MainUiState> = combine(
        selectedCategory,
        searchQuery.debounce(300)
    ) { category, query -> category to query }
        .flatMapLatest { (category, query) -> getChannelsUseCase(category, query) }
        .map { channels ->
            MainUiState(channels, channels.map { it.categoryName }.distinct().sorted(), selectedCategory.value, searchQuery.value)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainUiState())

    fun onCategorySelected(category: String?) { selectedCategory.value = category }
    fun onSearchQueryChanged(query: String) { searchQuery.value = query }
    fun onToggleFavorite(id: String) { viewModelScope.launch { repository.toggleFavorite(id) } }
}

@OptIn(UnstableApi::class)
@Singleton
class IptvPlayerManager @Inject constructor(@ApplicationContext private val context: Context) {
    private var player: ExoPlayer? = null
    fun getPlayer() = player ?: ExoPlayer.Builder(context).build().also { player = it }

    fun playChannel(channel: Channel) {
        val p = getPlayer()
        val httpFactory = DefaultHttpDataSource.Factory().apply { channel.userAgent?.let { setUserAgent(it) } }
        val mediaSource = HlsMediaSource.Factory(httpFactory).createMediaSource(
            MediaItem.Builder().setUri(channel.streamUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        )
        p.setMediaSource(mediaSource)
        p.prepare()
        p.playWhenReady = true
    }
    fun togglePlayPause() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun setVolume(v: Float) { player?.volume = v }
    fun release() { player?.release(); player = null }
}

@HiltViewModel
class PlayerViewModel @Inject constructor(val playerManager: IptvPlayerManager) : ViewModel() {
    var currentChannel by mutableStateOf<Channel?>(null)
    var isBuffering by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var volume by mutableStateOf(1f)

    init {
        playerManager.getPlayer().addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { isBuffering = state == Player.STATE_BUFFERING }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        })
    }

    fun play(channel: Channel) {
        currentChannel = channel
        playerManager.playChannel(channel)
    }
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun setVolume(v: Float) { volume = v; playerManager.setVolume(v) }
    override fun onCleared() { super.onCleared(); playerManager.release() }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onChannelClick: (Channel) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var searchText by remember { mutableStateOf("") }
    
    LaunchedEffect(searchText) { viewModel.onSearchQueryChanged(searchText) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = searchText, onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Поиск...") }, leadingIcon = { Icon(Icons.Default.Search, null) }
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.channels, key = { it.id }) { channel ->
                Card(modifier = Modifier.clickable { onChannelClick(channel) }) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(80.dp), contentScale = ContentScale.Fit)
                        Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, playerManager: IptvPlayerManager, onBack: () -> Unit) {
    val channel = viewModel.currentChannel
    LaunchedEffect(channel) { channel?.let { viewModel.play(it) } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = playerManager.getPlayer(); useController = false } },
            modifier = Modifier.fillMaxSize()
        )
        if (viewModel.isBuffering) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

        Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(0.5f)).padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { viewModel.togglePlayPause() }) {
                Icon(if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
            }
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        }
    }
}

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var playerManager: IptvPlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                var showPlayer by remember { mutableStateOf(false) }
                val mainVm: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val playerVm: PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()

                if (!showPlayer) {
                    MainScreen(mainVm) { channel ->
                        playerVm.currentChannel = channel
                        showPlayer = true
                    }
                } else {
                    PlayerScreen(playerVm, playerManager) { showPlayer = false }
                }
            }
        }
    }
}ener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { isBuffering = state == Player.STATE_BUFFERING }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        })
    }

    fun play(channel: Channel) {
        currentChannel = channel
        playerManager.playChannel(channel)
    }
    fun togglePlayPause() = playerManager.togglePlayPause()
    fun setVolume(v: Float) { volume = v; playerManager.setVolume(v) }
    override fun onCleared() { super.onCleared(); playerManager.release() }
}

@Composable
fun MainScreen(viewModel: MainViewModel, onChannelClick: (Channel) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var searchText by remember { mutableStateOf("") }
    
    LaunchedEffect(searchText) { viewModel.onSearchQueryChanged(searchText) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = searchText, onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Поиск...") }, leadingIcon = { Icon(Icons.Default.Search, null) }
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.channels, key = { it.id }) { channel ->
                Card(modifier = Modifier.clickable { onChannelClick(channel) }) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().height(80.dp), contentScale = ContentScale.Fit)
                        Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(viewModel: PlayerViewModel, playerManager: IptvPlayerManager, onBack: () -> Unit) {
    val channel = viewModel.currentChannel
    LaunchedEffect(channel) { channel?.let { viewModel.play(it) } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { player = playerManager.getPlayer(); useController = false } },
            modifier = Modifier.fillMaxSize()
        )
        if (viewModel.isBuffering) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

        Row(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(0.5f)).padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { viewModel.togglePlayPause() }) {
                Icon(if (viewModel.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
            }
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
        }
    }
}

@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var playerManager: IptvPlayerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = DarkColorScheme) {
                var showPlayer by remember { mutableStateOf(false) }
                val mainVm: MainViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                val playerVm: PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()

                if (!showPlayer) {
                    MainScreen(mainVm) { channel ->
                        playerVm.currentChannel = channel
                        showPlayer = true
                    }
                } else {
                    PlayerScreen(playerVm, playerManager) { showPlayer = false }
                }
            }
        }
    }
}
