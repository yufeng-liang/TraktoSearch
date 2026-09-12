package com.tracktosearch.ui.screen.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.ai.AiDetailAnalysis
import com.tracktosearch.data.ai.AiDetailAnalyzeRequest
import com.tracktosearch.data.ai.AiDetailEnvironmentDto
import com.tracktosearch.data.ai.AiDetailMediaDto
import com.tracktosearch.data.ai.AiDetailRecommendation
import com.tracktosearch.data.ai.AiErrorCode
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.AiProfileBehaviorRecorder
import com.tracktosearch.data.ai.AiProfileRepository
import com.tracktosearch.data.ai.AiProfileSettings
import com.tracktosearch.data.ai.AiProfileSettingsRequest
import com.tracktosearch.data.ai.AiProfileSyncCoordinator
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.ai.MediaSourceSnapshot
import com.tracktosearch.data.ai.toDomain
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.repository.WeatherRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailAiMediaInput(
    val mediaKey: String,
    val mediaType: String,
    val title: String,
    val year: Int?,
    val genres: List<String>,
    val overview: String?,
    val directors: List<String>,
    val cast: List<String>,
    val publicRating: Double?,
    val mediaIds: AiMediaIdsDto,
    val userRating: Int?,
    val userComment: String?,
    val watched: Boolean,
    val watchlist: Boolean
)

data class DetailAiUiState(
    val scene: DetailAiScene = DetailAiScene.UNMARKED,
    val profileConsent: Boolean = false,
    val behaviorConsent: Boolean = false,
    val syncEnabled: Boolean = false,
    val profileSettingsLoaded: Boolean = false,
    val isAuthorized: Boolean = false,
    val spriteEnabled: Boolean = false,
    val spriteVisible: Boolean = false,
    val panelVisible: Boolean = false,
    val interruptRevision: Long = 0L,
    val interruptReason: com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason =
        com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason.BLOCKED,
    val analysis: AiDetailAnalysis? = null,
    val isLoading: Boolean = false,
    val errorCode: AiErrorCode? = null,
    val recommendations: List<RecommendationItem> = emptyList(),
    val recommendationsLoaded: Boolean = false,
    val isLoadingRecommendations: Boolean = false,
    val recommendationsErrorCode: AiErrorCode? = null,
    val environment: DetailEnvironment? = null
)

@HiltViewModel
class DetailAiViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val aiProfileRepository: AiProfileRepository,
    private val aiProfileSyncCoordinator: AiProfileSyncCoordinator,
    private val aiProfileBehaviorRecorder: AiProfileBehaviorRecorder,
    private val authManager: AuthManager,
    private val weatherRepository: WeatherRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailAiUiState())
    val uiState: StateFlow<DetailAiUiState> = _uiState.asStateFlow()

    private var currentInput: DetailAiMediaInput? = null
    private var sessionKey: String? = null
    private var screenEnteredAt = 0L
    private var screenActive = false
    private var foregroundReady = false
    private var alreadyRevealed = false
    private var dwellJob: Job? = null
    private var analysisJob: Job? = null
    private var recommendationJob: Job? = null
    private var mirrorFingerprint: String? = null
    private var lastRecommendationCandidates: List<RecommendationItem> = emptyList()

    init {
        viewModelScope.launch {
            authManager.friendId.collectLatest { friendId ->
                refreshConsent(friendId)
            }
        }
    }

    /** 进入一部新的影视详情，计时从这里开始，且每次媒体访问只揭示一次探头。 */
    fun startSession(input: DetailAiMediaInput) {
        if (sessionKey == input.mediaKey && screenActive) {
            updateMedia(input)
            return
        }
        dwellJob?.cancel()
        analysisJob?.cancel()
        recommendationJob?.cancel()
        sessionKey = input.mediaKey
        currentInput = input
        screenEnteredAt = System.currentTimeMillis()
        screenActive = true
        foregroundReady = false
        alreadyRevealed = false
        mirrorFingerprint = null
        lastRecommendationCandidates = emptyList()
        _uiState.value = DetailAiUiState(
            scene = sceneFor(input),
            spriteEnabled = _uiState.value.spriteEnabled,
            profileConsent = _uiState.value.profileConsent,
            behaviorConsent = _uiState.value.behaviorConsent,
            syncEnabled = _uiState.value.syncEnabled,
            profileSettingsLoaded = _uiState.value.profileSettingsLoaded,
            isAuthorized = _uiState.value.isAuthorized
        )
        scheduleReveal()
        mirrorIfAllowed()
    }

    /** 详情富化、评分或标记状态变化时更新当前媒体，不重置本次 3.5 秒计时。 */
    fun updateMedia(input: DetailAiMediaInput) {
        if (sessionKey != input.mediaKey) {
            startSession(input)
            return
        }
        if (!screenActive) {
            currentInput = input
            resumeSession()
        }
        val previousInput = currentInput
        val reviewChanged = previousInput != null && (
            previousInput.userRating != input.userRating ||
                previousInput.userComment != input.userComment
            )
        currentInput = input
        val nextScene = sceneFor(input)
        _uiState.update { current ->
            current.copy(
                scene = nextScene,
                analysis = if (current.scene == nextScene) current.analysis else null,
                spriteVisible = if (current.scene == nextScene) current.spriteVisible else false,
                recommendations = if (reviewChanged) emptyList() else current.recommendations,
                recommendationsLoaded = if (nextScene == DetailAiScene.WATCHED_REVIEWED && !reviewChanged) {
                    current.recommendationsLoaded
                } else false
            )
        }
        mirrorIfAllowed()
    }

    fun setSpriteEnabled(enabled: Boolean) {
        _uiState.update { it.copy(spriteEnabled = enabled, spriteVisible = it.spriteVisible && enabled) }
        if (enabled) scheduleReveal()
    }

    fun setForegroundReady(ready: Boolean) {
        foregroundReady = ready
        if (ready) scheduleReveal() else interrupt(
            com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason.BLOCKED
        )
    }

    fun resumeSession() {
        if (currentInput == null || screenActive) return
        screenActive = true
        screenEnteredAt = System.currentTimeMillis()
        foregroundReady = false
        alreadyRevealed = false
        _uiState.update {
            it.copy(
                spriteVisible = false,
                panelVisible = false
            )
        }
        scheduleReveal()
    }

    fun onSpriteFinished() {
        _uiState.update { it.copy(spriteVisible = false) }
    }

    fun onSpriteClick() {
        if (!_uiState.value.spriteVisible) return
        _uiState.update { it.copy(spriteVisible = false, panelVisible = true) }
        interrupt(com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason.USER_INPUT)
        when (_uiState.value.scene) {
            DetailAiScene.UNMARKED,
            DetailAiScene.WATCHLIST_CONTEXT -> analyze()
            DetailAiScene.WATCHED_NEEDS_REVIEW,
            DetailAiScene.WATCHED_REVIEWED -> Unit
        }
    }

    fun dismissPanel() {
        _uiState.update { it.copy(panelVisible = false) }
        interrupt(com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason.USER_INPUT)
    }

    fun retryAnalysis() {
        analyze(forceRefresh = true)
    }

    fun grantProfileConsent() {
        val friendId = authManager.friendId.value?.trim().orEmpty()
        if (friendId.isBlank()) return
        viewModelScope.launch {
            val local = runCatching {
                aiProfileRepository.grantProfileConsent(friendId)
            }.getOrNull()
            val remote = aiRepository.updateProfileSettings(
                friendId,
                AiProfileSettingsRequest(
                    profileConsent = true,
                    personalizationEnabled = true,
                    syncEnabled = true
                )
            ).getOrNull()
            val settings = local ?: remote?.toDomain(friendId)
            if (settings != null) applySettings(settings)
            if (settings?.profileConsent == true) {
                mirrorIfAllowed()
                _uiState.update {
                    it.copy(
                        recommendations = emptyList(),
                        recommendationsLoaded = false,
                        recommendationsErrorCode = null
                    )
                }
                if (_uiState.value.scene == DetailAiScene.UNMARKED ||
                    _uiState.value.scene == DetailAiScene.WATCHLIST_CONTEXT
                ) {
                    analyze()
                }
            }
        }
    }

    /** 相关推荐只在用户真正打开相关推荐 Tab 后调用。 */
    fun loadRecommendations(existing: List<RecommendationItem>) {
        if (_uiState.value.scene != DetailAiScene.WATCHED_REVIEWED ||
            _uiState.value.recommendationsLoaded ||
            _uiState.value.isLoadingRecommendations
        ) return
        if (existing.isEmpty()) return
        lastRecommendationCandidates = existing
        val input = currentInput ?: return
        val friendId = authManager.friendId.value?.trim().orEmpty()
        if (!canUseProfile(friendId)) {
            _uiState.update { it.copy(recommendationsLoaded = true, recommendations = existing) }
            return
        }
        val candidates = existing.mapNotNull {
            detailRecommendationWire(it, input.mediaType)
        }
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(recommendationsLoaded = true, recommendations = existing) }
            return
        }
        recommendationJob?.cancel()
        recommendationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingRecommendations = true,
                    recommendationsErrorCode = null
                )
            }
            aiRepository.rankDetailRecommendations(
                friendId,
                com.tracktosearch.data.ai.AiRecommendationsRankRequest(
                    currentMedia = input.toAiMedia(),
                    candidates = candidates
                )
            ).onSuccess { ranked ->
                val aiItems = ranked.mapNotNull(::detailRecommendationFromAi)
                _uiState.update {
                    it.copy(
                        recommendations = mergeAiRecommendations(input.mediaKey, existing, aiItems),
                        recommendationsLoaded = true,
                        isLoadingRecommendations = false
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        recommendations = existing,
                        recommendationsLoaded = true,
                        isLoadingRecommendations = false,
                        recommendationsErrorCode = (error as? com.tracktosearch.data.ai.AiApiException)?.errorCode
                    )
                }
            }
        }
    }

    fun onScroll() {
        interrupt(com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason.SCROLL)
    }

    fun onScreenStopped() {
        if (!screenActive) return
        screenActive = false
        dwellJob?.cancel()
        interrupt(com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason.NAVIGATION)
        val input = currentInput ?: return
        val durationMs = (System.currentTimeMillis() - screenEnteredAt).coerceAtLeast(0L)
        if (!shouldRecordDetailDwell(durationMs)) return
        viewModelScope.launch {
            runCatching {
                aiProfileBehaviorRecorder.recordDetailDwell(
                    snapshot = input.toMediaSourceSnapshot(),
                    durationMs = durationMs
                )
            }.onFailure { error ->
                Log.d(
                    "DetailAiViewModel",
                    "AI detail dwell recording failed",
                    error
                )
            }
        }
    }

    private fun scheduleReveal() {
        val input = currentInput ?: return
        if (!screenActive || !foregroundReady || alreadyRevealed || !_uiState.value.spriteEnabled) return
        if (input.mediaKey.isBlank()) return
        dwellJob?.cancel()
        val elapsed = (System.currentTimeMillis() - screenEnteredAt).coerceAtLeast(0L)
        dwellJob = viewModelScope.launch {
            delay((DETAIL_SPRITE_REVEAL_MS - elapsed).coerceAtLeast(0L))
            if (screenActive && foregroundReady && !alreadyRevealed && _uiState.value.spriteEnabled) {
                alreadyRevealed = true
                _uiState.update { it.copy(spriteVisible = true) }
            }
        }
    }

    private fun analyze(forceRefresh: Boolean = false) {
        val input = currentInput ?: return
        if (_uiState.value.scene == DetailAiScene.WATCHED_REVIEWED ||
            _uiState.value.scene == DetailAiScene.WATCHED_NEEDS_REVIEW
        ) return
        val friendId = authManager.friendId.value?.trim().orEmpty()
        if (!canUseProfile(friendId)) return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorCode = null) }
            val environment = if (_uiState.value.scene == DetailAiScene.WATCHLIST_CONTEXT) {
                loadEnvironment()
            } else null
            _uiState.update { it.copy(environment = environment) }
            aiRepository.analyzeDetail(
                friendId = friendId,
                request = AiDetailAnalyzeRequest(
                    media = input.toAiMedia(),
                    scene = _uiState.value.scene.wireName,
                    environment = environment?.toDto()
                ),
                forceRefresh = forceRefresh
            ).onSuccess { analysis ->
                _uiState.update { it.copy(analysis = analysis, isLoading = false) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorCode = (error as? com.tracktosearch.data.ai.AiApiException)?.errorCode
                    )
                }
            }
        }
    }

    private suspend fun loadEnvironment(): DetailEnvironment {
        val weather = runCatching { weatherRepository.getCachedCurrentWeather() }.getOrNull()
        return detailTodayEnvironment(
            weatherTag = weather?.let { weatherTagFor(it.weatherCode, it.temperature) }
        )
    }

    private suspend fun refreshConsent(friendIdValue: String?) {
        val friendId = friendIdValue?.trim().orEmpty()
        if (friendId.isBlank() || authManager.authState.value !in setOf(AuthState.AUTHORIZED, AuthState.OFFLINE)) {
            _uiState.update {
                it.copy(
                    profileConsent = false,
                    behaviorConsent = false,
                    syncEnabled = false,
                    profileSettingsLoaded = true,
                    isAuthorized = false
                )
            }
            return
        }
        val local = runCatching { aiProfileRepository.settings(friendId) }.getOrNull()
        val settings = local ?: aiRepository.getProfileSettings(friendId).getOrNull()?.toDomain(friendId)
        if (settings != null) applySettings(settings) else {
            _uiState.update { it.copy(profileSettingsLoaded = true, isAuthorized = true) }
        }
        mirrorIfAllowed()
    }

    private fun applySettings(settings: AiProfileSettings) {
        _uiState.update {
            it.copy(
                profileConsent = settings.profileConsent,
                behaviorConsent = settings.behaviorConsent,
                syncEnabled = settings.syncEnabled,
                profileSettingsLoaded = true,
                isAuthorized = true
            )
        }
    }

    private fun canUseProfile(friendId: String): Boolean =
        friendId.isNotBlank() &&
            _uiState.value.isAuthorized &&
            _uiState.value.profileConsent

    private fun mirrorIfAllowed() {
        val input = currentInput ?: return
        val friendId = authManager.friendId.value?.trim().orEmpty()
        if (!canUseProfile(friendId)) return
        val fingerprint = listOf(
            input.mediaKey,
            input.userRating,
            input.userComment.orEmpty(),
            input.watched,
            input.watchlist,
            input.title,
            input.overview.orEmpty()
        ).joinToString("|")
        if (mirrorFingerprint == fingerprint) return
        mirrorFingerprint = fingerprint
        viewModelScope.launch {
            runCatching {
                aiProfileRepository.mirrorMedia(friendId, input.toMediaSourceSnapshot())
                if (_uiState.value.syncEnabled) aiProfileSyncCoordinator.syncNow(friendId)
            }
        }
    }

    private fun interrupt(reason: com.tracktosearch.ui.screen.ai.AiSpriteInterruptReason) {
        _uiState.update {
            it.copy(
                spriteVisible = false,
                interruptRevision = it.interruptRevision + 1L,
                interruptReason = reason
            )
        }
    }

    private fun sceneFor(input: DetailAiMediaInput): DetailAiScene = detailAiScene(
        watched = input.watched,
        rating = input.userRating,
        comment = input.userComment,
        watchlist = input.watchlist
    )
}

private fun DetailAiMediaInput.toMediaSourceSnapshot(): MediaSourceSnapshot = MediaSourceSnapshot(
    mediaType = mediaType,
    tmdbId = mediaIds.tmdbId,
    traktId = mediaIds.traktId?.toIntOrNull(),
    imdbId = mediaIds.imdbId,
    doubanId = mediaIds.doubanId,
    title = title,
    year = year,
    genres = genres,
    publicRating = publicRating,
    userRating = userRating?.toDouble(),
    userComment = userComment,
    isWatched = watched,
    isWatchlist = watchlist
)

private fun DetailAiMediaInput.toAiMedia(): AiDetailMediaDto = AiDetailMediaDto(
    mediaKey = mediaKey,
    mediaType = mediaType,
    title = title,
    year = year,
    genres = genres,
    overview = overview,
    directors = directors,
    cast = cast,
    publicRating = publicRating,
    mediaIds = mediaIds,
    userRating = userRating?.toDouble(),
    userComment = userComment
)

private fun DetailEnvironment.toDto(): AiDetailEnvironmentDto = AiDetailEnvironmentDto(
    localDate = localDate,
    weekday = weekday,
    timeOfDay = timeOfDay,
    season = season,
    weatherTag = weatherTag
)
