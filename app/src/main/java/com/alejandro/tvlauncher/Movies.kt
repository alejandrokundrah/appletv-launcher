package com.alejandro.tvlauncher

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed

// ------------------------------------------------------------------------------------ TMDB

data class TmdbTitle(
    val id: Int,
    val isTv: Boolean,
    val title: String,
    val year: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
)

data class TmdbSeason(val number: Int, val episodes: Int)

data class TmdbEpisode(val number: Int, val name: String, val still: String?, val runtime: Int)

data class TmdbDetail(val genres: String, val runtime: Int, val seasons: List<TmdbSeason>)

data class TmdbCast(val name: String, val character: String, val photo: String?)

data class TmdbExtras(val cast: List<TmdbCast>, val similar: List<TmdbTitle>)

data class TmdbRow(val titleRes: Int, val items: List<TmdbTitle>)

/** What the player screen is asked to play. [season] / [episode] are 0 for movies. */
data class PlayRequest(val title: TmdbTitle, val season: Int, val episode: Int, val seasons: List<TmdbSeason>)

/** Minimal TMDB v3 client (HttpURLConnection + org.json). Details are cached for the session. */
object Tmdb {

    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p/"

    private val detailCache = HashMap<String, TmdbDetail>()
    private val episodeCache = HashMap<String, List<TmdbEpisode>>()
    private val extrasCache = HashMap<String, TmdbExtras>()

    fun image(path: String, size: String): String = "$IMAGES$size$path"

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private suspend fun fetch(path: String, vararg params: Pair<String, String>): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val sb = StringBuilder("$API$path?api_key=${MiniConfig.TMDB_KEY}")
                sb.append("&language=").append(Uri.encode(Locale.getDefault().toLanguageTag()))
                for ((k, v) in params) sb.append('&').append(k).append('=').append(Uri.encode(v))
                val conn = URL(sb.toString()).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 12_000
                    if (conn.responseCode == 200) {
                        JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    /** [forceTv] null = mixed list (search / trending/all): the `media_type` of each entry decides. */
    private fun parse(arr: JSONArray?, forceTv: Boolean?): List<TmdbTitle> {
        if (arr == null) return emptyList()
        val out = ArrayList<TmdbTitle>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.str("media_type")
            val tv = forceTv ?: (type == "tv")
            if (forceTv == null && type != "tv" && type != "movie") continue
            val id = o.optInt("id", -1)
            if (id < 0) continue
            val title = (if (tv) o.str("name") else o.str("title")) ?: o.str("original_title") ?: o.str("original_name") ?: continue
            val date = (if (tv) o.str("first_air_date") else o.str("release_date")).orEmpty()
            out += TmdbTitle(
                id = id,
                isTv = tv,
                title = title,
                year = date.take(4),
                overview = o.str("overview").orEmpty(),
                poster = o.str("poster_path"),
                backdrop = o.str("backdrop_path"),
                rating = o.optDouble("vote_average", 0.0),
            )
        }
        return out
    }

    private suspend fun list(path: String, tv: Boolean): List<TmdbTitle> =
        parse(fetch(path)?.optJSONArray("results"), tv)

    /** Weekly movie trends used by the launcher homepage. */
    suspend fun trendingMovies(): List<TmdbTitle> = list("/trending/movie/week", false)

    /** Home rows for the Movies or TV Shows tab (rows that failed to load are left out). */
    suspend fun rows(tv: Boolean): List<TmdbRow> = coroutineScope {
        val specs = if (tv) {
            listOf(
                R.string.row_trending to "/trending/tv/week",
                R.string.row_popular to "/tv/popular",
                R.string.row_top_rated to "/tv/top_rated",
                R.string.row_on_air to "/tv/on_the_air",
            )
        } else {
            listOf(
                R.string.row_trending to "/trending/movie/week",
                R.string.row_popular to "/movie/popular",
                R.string.row_top_rated to "/movie/top_rated",
                R.string.row_now_playing to "/movie/now_playing",
            )
        }
        specs.map { (res, path) -> async { TmdbRow(res, list(path, tv)) } }
            .awaitAll()
            .filter { it.items.isNotEmpty() }
    }

    suspend fun search(query: String): List<TmdbTitle> =
        parse(fetch("/search/multi", "query" to query, "include_adult" to "false")?.optJSONArray("results"), null)

    suspend fun detail(t: TmdbTitle): TmdbDetail? {
        val key = (if (t.isTv) "tv" else "movie") + t.id
        detailCache[key]?.let { return it }
        val o = fetch(if (t.isTv) "/tv/${t.id}" else "/movie/${t.id}") ?: return null
        val genres = o.optJSONArray("genres")?.let { g ->
            (0 until g.length()).mapNotNull { g.optJSONObject(it)?.str("name") }.joinToString(", ")
        }.orEmpty()
        val runtime = if (t.isTv) {
            o.optJSONArray("episode_run_time")?.optInt(0, 0) ?: 0
        } else {
            o.optInt("runtime", 0)
        }
        val seasons = o.optJSONArray("seasons")?.let { s ->
            (0 until s.length()).mapNotNull { i ->
                val so = s.optJSONObject(i) ?: return@mapNotNull null
                val n = so.optInt("season_number", -1)
                val c = so.optInt("episode_count", 0)
                // season 0 = "Specials": skipped, the player's next / previous episode follows the real seasons
                if (n > 0 && c > 0) TmdbSeason(n, c) else null
            }
        }.orEmpty()
        return TmdbDetail(genres, runtime, seasons).also { detailCache[key] = it }
    }

    /** Top-billed cast plus "more like this" (TMDB recommendations, or similar titles when there are none). */
    suspend fun extras(t: TmdbTitle): TmdbExtras {
        val kind = if (t.isTv) "tv" else "movie"
        val key = "$kind${t.id}"
        extrasCache[key]?.let { return it }
        val (credits, recs, sim) = coroutineScope {
            val c = async { fetch("/$kind/${t.id}/credits") }
            val r = async { fetch("/$kind/${t.id}/recommendations") }
            val s = async { fetch("/$kind/${t.id}/similar") }
            Triple(c.await(), r.await(), s.await())
        }
        val castArr = credits?.optJSONArray("cast")
        val cast = ArrayList<TmdbCast>()
        if (castArr != null) {
            for (i in 0 until minOf(castArr.length(), 15)) {
                val o = castArr.optJSONObject(i) ?: continue
                val name = o.str("name") ?: continue
                cast += TmdbCast(name, o.str("character").orEmpty(), o.str("profile_path"))
            }
        }
        var similar = parse(recs?.optJSONArray("results"), t.isTv)
        if (similar.isEmpty()) similar = parse(sim?.optJSONArray("results"), t.isTv)
        similar = similar.filter { it.id != t.id }.take(20)
        val out = TmdbExtras(cast, similar)
        // Don't cache a total failure (offline), so reopening the page tries again.
        if (cast.isNotEmpty() || similar.isNotEmpty()) extrasCache[key] = out
        return out
    }

    suspend fun episodes(tvId: Int, season: Int): List<TmdbEpisode> {
        val key = "$tvId/$season"
        episodeCache[key]?.let { return it }
        val arr = fetch("/tv/$tvId/season/$season")?.optJSONArray("episodes") ?: return emptyList()
        val eps = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            TmdbEpisode(
                number = e.optInt("episode_number", i + 1),
                name = e.str("name") ?: "",
                still = e.str("still_path"),
                runtime = e.optInt("runtime", 0),
            )
        }
        if (eps.isNotEmpty()) episodeCache[key] = eps
        return eps
    }
}

// ------------------------------------------------------------------------------------ screens

private val MiniBg = Color(0xFF07070C)

/** Movies & TV mini app: browse (TMDB) → details → play (our player in a WebView). */
@Composable
fun MoviesApp(settings: LauncherSettings, initialTitle: TmdbTitle? = null, lite: Boolean, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var movieRows by remember { mutableStateOf<List<TmdbRow>?>(null) }
    var tvRows by remember { mutableStateOf<List<TmdbRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TmdbTitle>?>(null) }
    var restoreKey by remember { mutableStateOf("") }
    // Opened titles; "More like this" pushes onto it so Back returns to the previous title.
    var trail by remember { mutableStateOf<List<TmdbTitle>>(emptyList()) }
    var detailSeason by remember { mutableIntStateOf(1) }
    var play by remember { mutableStateOf<PlayRequest?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Horizontal scroll position of every row, kept while a title's details are open.
    val rowStates = remember { HashMap<String, LazyListState>() }

    LaunchedEffect(initialTitle) {
        if (initialTitle != null && trail.isEmpty()) {
            trail = listOf(initialTitle)
            detailSeason = 1
        }
    }

    LaunchedEffect(tab, reload) {
        failed = false
        if (tab == 0 && movieRows == null) {
            val r = Tmdb.rows(false)
            if (r.isEmpty()) failed = true else movieRows = r
        } else if (tab == 1 && tvRows == null) {
            val r = Tmdb.rows(true)
            if (r.isEmpty()) failed = true else tvRows = r
        }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
        } else {
            results = null
            delay(450)
            results = Tmdb.search(query.trim())
        }
    }

    val p = play
    val d = trail.lastOrNull()
    when {
        p != null -> VodPlayer(
            req = p,
            settings = settings,
            onBack = { play = null },
            onEpisode = { s, e ->
                detailSeason = s
                play = p.copy(season = s, episode = e)
            },
        )
        d != null -> DetailScreen(
            t = d,
            season = detailSeason,
            onSeason = { detailSeason = it },
            onPlay = { play = it },
            onOpenTitle = { detailSeason = 1; trail = trail + it },
            onBack = { trail = trail.dropLast(1) },
        )
        else -> BrowseScreen(
            tab = tab,
            onTab = { tab = it },
            rows = if (tab == 0) movieRows else tvRows,
            failed = failed,
            onRetry = { reload++ },
            query = query,
            onQuery = { query = it },
            results = results,
            listState = listState,
            gridState = gridState,
            rowStates = rowStates,
            restoreKey = restoreKey,
            onOpen = { title, key ->
                restoreKey = key
                detailSeason = 1
                trail = listOf(title)
            },
            onClose = onClose,
        )
    }
}

@Composable
private fun BrowseScreen(
    tab: Int,
    onTab: (Int) -> Unit,
    rows: List<TmdbRow>?,
    failed: Boolean,
    onRetry: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    results: List<TmdbTitle>?,
    listState: LazyListState,
    gridState: LazyGridState,
    rowStates: HashMap<String, LazyListState>,
    restoreKey: String,
    onOpen: (TmdbTitle, String) -> Unit,
    onClose: () -> Unit,
) {
    val searching = query.isNotBlank()
    BackHandler(enabled = true) { if (query.isNotEmpty()) onQuery("") else onClose() }

    val cardW: Dp = (LocalConfiguration.current.screenWidthDp * 0.115f).dp
    val cardH: Dp = cardW * 3 / 2
    val target = remember { FocusRequester() }
    val retry = remember { FocusRequester() }

    // Put focus on a card once there is something to show: the first one, or the one just opened.
    val ready = if (searching) !results.isNullOrEmpty() else !rows.isNullOrEmpty()
    LaunchedEffect(ready) {
        if (ready) {
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    LaunchedEffect(failed) {
        if (failed) {
            withFrameNanos { }
            runCatching { retry.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(MiniScrim)) {
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GText(stringResource(R.string.movies_title), 40.sp, FontWeight.Bold, Color.White, maxLines = 1)
                Spacer(Modifier.width(28.dp))
                MiniButton(stringResource(R.string.movies_tab_movies), selected = tab == 0 && !searching) { onTab(0) }
                Spacer(Modifier.width(12.dp))
                MiniButton(stringResource(R.string.movies_tab_tv), selected = tab == 1 && !searching) { onTab(1) }
                Spacer(Modifier.weight(1f))
                MiniSearchField(
                    value = query,
                    onValueChange = onQuery,
                    hint = stringResource(R.string.movies_search_hint),
                    modifier = Modifier.width(380.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            when {
                searching -> when {
                    results == null -> MiniMessage(stringResource(R.string.movies_searching))
                    results.isEmpty() -> MiniMessage(stringResource(R.string.movies_no_results))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = cardW),
                        state = gridState,
                        contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 18.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItemsIndexed(results) { index, t ->
                            val key = "s:$index"
                            MiniCard(
                                title = t.title,
                                imageUrl = t.poster?.let { Tmdb.image(it, "w342") },
                                w = cardW,
                                h = cardH,
                                subtitle = subtitleOf(t),
                                requester = if (key == restoreKey || (index == 0 && !restoreKey.startsWith("s:"))) target else null,
                                onClick = { onOpen(t, key) },
                            )
                        }
                    }
                }
                failed -> {
                    MiniMessage(stringResource(R.string.movies_failed))
                    Spacer(Modifier.height(24.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        MiniButton(stringResource(R.string.movies_retry), primary = true, requester = retry, onClick = onRetry)
                    }
                }
                rows == null -> MiniMessage(stringResource(R.string.movies_loading))
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    listItemsIndexed(rows) { rowIndex, row ->
                        val rowState = rowStates.getOrPut("$tab:$rowIndex") { LazyListState() }
                        Column {
                            GText(
                                stringResource(row.titleRes), 22.sp, FontWeight.SemiBold,
                                Color.White.copy(alpha = 0.88f),
                                modifier = Modifier.padding(start = 64.dp, top = 10.dp),
                                shadow = true,
                            )
                            LazyRow(
                                state = rowState,
                                contentPadding = PaddingValues(horizontal = 64.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                listItemsIndexed(row.items) { index, t ->
                                    val key = "$tab:$rowIndex:$index"
                                    val isTarget = key == restoreKey ||
                                        (rowIndex == 0 && index == 0 && restoreKey.isEmpty())
                                    MiniCard(
                                        title = t.title,
                                        imageUrl = t.poster?.let { Tmdb.image(it, "w342") },
                                        w = cardW,
                                        h = cardH,
                                        subtitle = subtitleOf(t),
                                        requester = if (isTarget) target else null,
                                        onClick = { onOpen(t, key) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "2023 · ★ 7.8" */
private fun subtitleOf(t: TmdbTitle): String? {
    val parts = ArrayList<String>(2)
    if (t.year.isNotEmpty()) parts += t.year
    if (t.rating > 0.0) parts += "★ " + String.format(Locale.US, "%.1f", t.rating)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun DetailScreen(
    t: TmdbTitle,
    season: Int,
    onSeason: (Int) -> Unit,
    onPlay: (PlayRequest) -> Unit,
    onOpenTitle: (TmdbTitle) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler(enabled = true) { onBack() }

    val detail by produceState<TmdbDetail?>(null, t) { value = Tmdb.detail(t) }
    val backdrop by produceState<ImageBitmap?>(null, t) {
        val path = t.backdrop ?: t.poster
        value = path?.let { PosterLoader.load(context, Tmdb.image(it, "w780")) }
    }
    val episodes by produceState<List<TmdbEpisode>?>(null, t, season) {
        value = null
        if (t.isTv) value = Tmdb.episodes(t.id, season)
    }

    val extras by produceState<TmdbExtras?>(null, t) { value = Tmdb.extras(t) }

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(t) {
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    val seasons = detail?.seasons.orEmpty()
    val firstEpisode = episodes?.firstOrNull()?.number ?: 1
    val screenW = LocalConfiguration.current.screenWidthDp
    val epW: Dp = (screenW * 0.19f).dp
    val epH: Dp = epW * 9 / 16
    val castW: Dp = (screenW * 0.085f).dp
    val posterW: Dp = (screenW * 0.105f).dp
    val screenH = LocalConfiguration.current.screenHeightDp

    Box(Modifier.fillMaxSize().background(MiniBg)) {
        backdrop?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xF2070710),
                    0.55f to Color(0xB3070710),
                    1f to Color(0x40070710),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xEB070710))
            )
        )

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = 64.dp, top = 56.dp, end = 64.dp, bottom = 48.dp)
        ) {
            GText(t.title, 52.sp, FontWeight.Bold, Color.White, maxLines = 2, shadow = true, modifier = Modifier.fillMaxWidth(0.7f))
            Spacer(Modifier.height(8.dp))

            val d = detail
            val meta = ArrayList<String>()
            if (t.year.isNotEmpty()) meta += t.year
            if (!t.isTv && d != null && d.runtime > 0) meta += "${d.runtime / 60}h ${d.runtime % 60}m"
            if (t.isTv && d != null && d.seasons.isNotEmpty()) {
                meta += context.resources.getQuantityString(R.plurals.movies_seasons_count, d.seasons.size, d.seasons.size)
            }
            if (t.rating > 0.0) meta += "★ " + String.format(Locale.US, "%.1f", t.rating)
            if (d != null && d.genres.isNotEmpty()) meta += d.genres
            if (meta.isNotEmpty()) {
                GText(meta.joinToString("  ·  "), 20.sp, FontWeight.Medium, Color.White.copy(alpha = 0.75f), maxLines = 1, shadow = true)
                Spacer(Modifier.height(14.dp))
            }
            GText(
                t.overview.ifEmpty { stringResource(R.string.movies_no_overview) },
                20.sp, FontWeight.Normal, Color.White.copy(alpha = 0.85f),
                maxLines = 6, shadow = true, modifier = Modifier.fillMaxWidth(0.55f),
            )

            Spacer(Modifier.height((screenH * 0.12f).dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniButton(
                    text = if (t.isTv) stringResource(R.string.movies_play_episode, season, firstEpisode)
                    else stringResource(R.string.movies_play),
                    primary = true,
                    requester = playFocus,
                ) {
                    onPlay(PlayRequest(t, if (t.isTv) season else 0, if (t.isTv) firstEpisode else 0, seasons))
                }
            }

            if (t.isTv) {
                if (seasons.size > 1) {
                    Spacer(Modifier.height(18.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                        items(seasons, key = { it.number }) { s ->
                            MiniButton(
                                text = stringResource(R.string.movies_season, s.number),
                                selected = s.number == season,
                                onClick = { onSeason(s.number) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val eps = episodes
                if (eps != null) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 6.dp),
                    ) {
                        items(eps, key = { it.number }) { ep ->
                            MiniCard(
                                title = ep.number.toString() + ". " + ep.name,
                                imageUrl = ep.still?.let { Tmdb.image(it, "w300") },
                                w = epW,
                                h = epH,
                                subtitle = if (ep.runtime > 0) "${ep.runtime} min" else null,
                                onClick = { onPlay(PlayRequest(t, season, ep.number, seasons)) },
                            )
                        }
                    }
                }
            }

            val ex = extras
            if (ex != null && ex.cast.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                GText(stringResource(R.string.movies_cast), 22.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.88f), shadow = true)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 6.dp),
                ) {
                    items(ex.cast) { c ->
                        MiniCard(
                            title = c.name,
                            imageUrl = c.photo?.let { Tmdb.image(it, "w185") },
                            w = castW,
                            h = castW * 3 / 2,
                            subtitle = c.character.ifEmpty { null },
                            onClick = {},
                        )
                    }
                }
            }
            if (ex != null && ex.similar.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                GText(stringResource(R.string.movies_more_like_this), 22.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.88f), shadow = true)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(vertical = 14.dp, horizontal = 6.dp),
                ) {
                    items(ex.similar, key = { it.id }) { s ->
                        MiniCard(
                            title = s.title,
                            imageUrl = s.poster?.let { Tmdb.image(it, "w342") },
                            w = posterW,
                            h = posterW * 3 / 2,
                            subtitle = subtitleOf(s),
                            onClick = { onOpenTitle(s) },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ player

/** Next (+1) / previous (-1) episode, crossing season borders. Null when there is none (or it's a movie). */
private fun PlayRequest.step(delta: Int): Pair<Int, Int>? {
    if (!title.isTv) return null
    val numbers = seasons.map { it.number }.sorted()
    val count = seasons.firstOrNull { it.number == season }?.episodes
    return if (delta > 0) {
        if (count == null || episode < count) {
            Pair(season, episode + 1)
        } else {
            val next = numbers.firstOrNull { it > season } ?: return null
            Pair(next, 1)
        }
    } else {
        if (episode > 1) {
            Pair(season, episode - 1)
        } else {
            val prev = numbers.lastOrNull { it < season } ?: return null
            val c = seasons.firstOrNull { it.number == prev }?.episodes ?: return null
            Pair(prev, c)
        }
    }
}

private fun playerUrl(base: String, r: PlayRequest): String {
    val type = if (r.title.isTv) "tv" else "movie"
    val suffix = if (r.title.isTv) "/${r.title.id}/${r.season}/${r.episode}" else "/${r.title.id}"
    val template = base.trimEnd('/')
    // `{tmdb_id}` is an alias for `{id}` used by several custom providers.
    // Treat it as a complete template too, otherwise the legacy suffix below
    // would be appended to an already-complete provider URL.
    val hasTemplate = listOf("{type}", "{id}", "{tmdb_id}", "{season}", "{episode}").any { it in template }
    return template.replace("{type}", type).replace("{id}", r.title.id.toString())
        .replace("{tmdb_id}", r.title.id.toString())
        .replace("{season}", r.season.toString()).replace("{episode}", r.episode.toString())
        .let { if (hasTemplate) it else it + "/$type$suffix" }
}

/** A custom provider may use separate movie and TV templates. Movie is first, TV second. */
private fun customPlayerTemplate(value: String, isTv: Boolean): String {
    val templates = value.lines().map { it.trim() }.filter { it.isNotBlank() }
    return when {
        templates.size >= 2 -> templates[if (isTv) 1 else 0]
        templates.isNotEmpty() -> templates[0]
        else -> value.trim()
    }
}

private fun customPlayerUrl(value: String, r: PlayRequest): String =
    playerUrl(customPlayerTemplate(value, r.title.isTv), r)

private fun playerUrls(r: PlayRequest, settings: LauncherSettings): List<String> = buildList {
    when (settings.movieServer.coerceIn(0, 4)) {
        1 -> add(playerUrl(MiniConfig.PLAYER_BASE, r))
        2 -> add(playerUrl(MiniConfig.ZXCSTREAM_BASE, r))
        3 -> add(playerUrl(MiniConfig.VIDSRC_BASE, r))
        4 -> if (settings.customPlayerUrl.isNotBlank()) add(customPlayerUrl(settings.customPlayerUrl, r))
        else -> {
            add(playerUrl(MiniConfig.PLAYER_BASE, r))
            add(playerUrl(MiniConfig.ZXCSTREAM_BASE, r))
            add(playerUrl(MiniConfig.VIDSRC_BASE, r))
            if (settings.customPlayerEnabled && settings.customPlayerUrl.isNotBlank()) {
                add(customPlayerUrl(settings.customPlayerUrl, r))
            }
        }
    }
    if (isEmpty()) add(playerUrl(MiniConfig.PLAYER_BASE, r))
}

private fun isPlayerHost(host: String?, allowedHosts: Set<String>): Boolean =
    host != null && allowedHosts.any { host == it || host.endsWith(".$it") }

/**
 * A WebView in a container that (a) sees remote keys first, so Next / Previous / Channel +/- can switch
 * episodes, and (b) hosts the page's full-screen video view.
 */
@SuppressLint("SetJavaScriptEnabled")
private class PlayerHost(context: Context) {

    var onKey: ((Int) -> Boolean)? = null
    var onLoadFailed: (() -> Unit)? = null
    var onPageLoaded: (() -> Unit)? = null
    var onPageUnavailable: (() -> Unit)? = null

    val root = object : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && onKey?.invoke(event.keyCode) == true) return true
            return super.dispatchKeyEvent(event)
        }
    }.apply { setBackgroundColor(android.graphics.Color.BLACK) }

    val web = WebView(context)
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    init {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        web.setBackgroundColor(android.graphics.Color.BLACK)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            // Ads love to redirect the whole page: only our own player is allowed to navigate the top frame.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return !isPlayerHost(request.url.host, allowedHosts)
            }

            // Android 6 and older call this variant instead.
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !isPlayerHost(Uri.parse(url).host, allowedHosts)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onLoadFailed?.invoke()
            }

            override fun onPageFinished(view: WebView, url: String) {
                onPageLoaded?.invoke()
                // A provider can return HTTP 200 with a normal-looking "not found" page.
                // Treat that as a failed server so the next configured server gets a chance.
                view.evaluateJavascript(
                    "(document.title + ' ' + (document.body ? document.body.innerText : '')).toLowerCase()"
                ) { text ->
                    if (Regex("not found|no results|unavailable|does not exist|couldn't find|cannot find").containsMatchIn(text)) {
                        onPageUnavailable?.invoke()
                    }
                }
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                onLoadFailed?.invoke()
            }

            // Some TV WebView builds crash their renderer on provider video pages. Keep the
            // launcher process alive so the next configured player can be tried.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                onLoadFailed?.invoke()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                (view.parent as? ViewGroup)?.removeView(view)
                customView = view
                customCallback = callback
                web.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private var allowedHosts: Set<String> = emptySet()

    fun setAllowedHosts(hosts: Set<String>) { allowedHosts = hosts }

    /** Leaves the page's full-screen video. Returns false when it wasn't in full screen. */
    fun hideCustom(): Boolean {
        val v = customView ?: return false
        root.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        web.visibility = View.VISIBLE
        web.requestFocus()
        return true
    }

    fun destroy() {
        hideCustom()
        onKey = null
        web.stopLoading()
        root.removeAllViews()
        web.destroy()
    }
}

/** Plays a movie / episode with the web player. Back leaves; Next / Previous (or Channel +/-) change episode. */
@Composable
private fun VodPlayer(settings: LauncherSettings, req: PlayRequest, onBack: () -> Unit, onEpisode: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val host = remember { PlayerHost(context) }
    val currentReq by rememberUpdatedState(req)
    val currentOnEpisode by rememberUpdatedState(onEpisode)
    val urls = remember(req, settings.movieServer, settings.customPlayerEnabled, settings.customPlayerUrl) {
        playerUrls(req, settings)
    }
    var serverIndex by remember(req) { mutableIntStateOf(0) }
    var pageLoaded by remember(req) { mutableStateOf(false) }
    val url = urls[serverIndex.coerceIn(0, urls.lastIndex)]
    var showTitle by remember { mutableStateOf(true) }

    host.onKey = { code ->
        when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                val s = currentReq.step(1)
                if (s != null) currentOnEpisode(s.first, s.second)
                currentReq.title.isTv
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                val s = currentReq.step(-1)
                if (s != null) currentOnEpisode(s.first, s.second)
                currentReq.title.isTv
            }
            else -> false
        }
    }

    host.setAllowedHosts(urls.mapNotNull { Uri.parse(it).host }.toSet())
    host.onLoadFailed = {
        if (serverIndex < urls.lastIndex) serverIndex++
    }
    host.onPageUnavailable = {
        if (serverIndex < urls.lastIndex) serverIndex++
    }
    host.onPageLoaded = { pageLoaded = true }

    DisposableEffect(host) { onDispose { host.destroy() } }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) host.web.onPause()
            if (event == Lifecycle.Event.ON_RESUME) host.web.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(url) {
        pageLoaded = false
        host.web.loadUrl(url)
        showTitle = true
        delay(10_000)
        if (!pageLoaded && serverIndex < urls.lastIndex) serverIndex++
        delay(3500)
        showTitle = false
    }
    BackHandler(enabled = true) { if (!host.hideCustom()) onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { host.root },
            modifier = Modifier.fillMaxSize(),
            update = { host.web.requestFocus() },
        )
        AnimatedVisibility(
            visible = showTitle,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            val label = if (req.title.isTv) {
                stringResource(R.string.movies_episode_label, req.season, req.episode, req.title.title)
            } else {
                req.title.title
            }
            GText(
                label, 24.sp, FontWeight.SemiBold, Color.White,
                maxLines = 1, shadow = true,
                modifier = Modifier.padding(start = 40.dp, top = 28.dp),
            )
        }
    }
}
