package com.tracktosearch.ui.screen.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.ai.AiActivateRequest
import com.tracktosearch.data.ai.AiAudio
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.data.ai.AiCharacterCatalog
import com.tracktosearch.data.ai.AiErrorCode
import com.tracktosearch.data.ai.AiGreeting
import com.tracktosearch.data.ai.AiQuiz
import com.tracktosearch.data.ai.AiQuizAnswer
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiQuizResult
import com.tracktosearch.data.ai.AiTasteAnalysis
import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.data.ai.AiMediaIdsDto
import com.tracktosearch.data.ai.AiDailyKnowledge
import com.tracktosearch.data.ai.AiRepository
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

enum class AiFeature {
    GREETING,
    TASTE,
    QUIZ,
    DAILY
}

enum class AiActivationState {
    IDLE,
    RECORDING,
    VERIFYING,
    SUCCESS,
    FAILED
}

data class AiSpriteUiState(
    val characters: List<AiCharacter> = AiCharacterCatalog.all,
    val selectedCharacterId: String = "usagi",
    val activatedCharacterId: String? = null,
    val authState: AuthState = AuthState.UNAUTHORIZED,
    val nickname: String? = null,
    val activationState: AiActivationState = AiActivationState.IDLE,
    val activationAttempt: Int = 0,
    val activationMessage: String? = null,
    val quota: com.tracktosearch.data.ai.AiQuota? = null,
    val isLoading: Boolean = false,
    val loadingFeature: AiFeature? = null,
    val activeFeature: AiFeature? = null,
    val greeting: AiGreeting? = null,
    val taste: AiTasteAnalysis? = null,
    // 口味分析成功加载次数，用于只触发一次对应场景图
    val tasteRevision: Long = 0L,
    val quiz: AiQuiz? = null,
    val quizPreviewMovies: List<AiWatchedTitleDto> = emptyList(),
    val quizReplacementCount: Int = 0,
    val quizStarted: Boolean = false,
    val quizIndex: Int = 0,
    val quizAnswers: Map<String, AiQuizAnswer> = emptyMap(),
    val quizResult: AiQuizResult? = null,
    // 答题结果成功提交次数，用于只触发一次对应场景图
    val quizResultRevision: Long = 0L,
    val quizHistory: com.tracktosearch.data.ai.AiQuizHistory? = null,
    val dailyKnowledge: AiDailyKnowledge? = null,
    val errorCode: String? = null
) {
    val isAuthorized: Boolean
        get() = authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE

    val selectedCharacter: AiCharacter?
        get() = characters.firstOrNull { it.id == selectedCharacterId }

    val activatedCharacter: AiCharacter?
        get() = activatedCharacterId?.let { id -> characters.firstOrNull { it.id == id } }
}

@HiltViewModel
class AiSpriteViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val authManager: AuthManager,
    private val traktRepository: TraktRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AiSpriteUiState(
            authState = authManager.authState.value,
            nickname = authManager.nickname.value
        )
    )
    val uiState: StateFlow<AiSpriteUiState> = _uiState.asStateFlow()

    private val _audioEvents = MutableSharedFlow<AiAudio>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioEvents: SharedFlow<AiAudio> = _audioEvents.asSharedFlow()

    private val _guestPreviewFallbackEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val guestPreviewFallbackEvents: SharedFlow<String> = _guestPreviewFallbackEvents.asSharedFlow()

    private var initialized = false
    private var previewJob: Job? = null
    private var requestJob: Job? = null
    /** 请求代际用于隔离已取消请求的 finally，避免旧请求覆盖新请求的加载态。 */
    private var requestGeneration = 0L
    private var recentQuizIds = emptyList<String>()
    private var quizCandidates = emptyList<AiWatchedTitleDto>()
    // 一次精灵中心会话共用一个会话 ID，让服务端会话配额按一次打开的精灵中心计算。
    // 若每次请求都发新 UUID，会话配额形同虚设，只剩每日上限。
    private val spriteSessionId = "sprite-${UUID.randomUUID()}"

    fun ensureLoaded() {
        if (initialized) {
            scheduleCharacterPreview()
            return
        }
        initialized = true
        viewModelScope.launch {
            launch {
                authManager.authState.collect { authState ->
                    _uiState.update {
                        it.copy(
                            authState = authState,
                            nickname = authManager.nickname.value
                        )
                    }
                }
            }
            launch {
                authManager.nickname.collect { nickname ->
                    _uiState.update { it.copy(nickname = nickname) }
                }
            }
            loadCharacters()
            scheduleCharacterPreview()
            loadQuizHistory()
        }
    }

    private suspend fun loadQuizHistory() {
        val friendId = authManager.friendId.value.orEmpty()
        if (friendId.isBlank()) return
        aiRepository.readQuizHistory(friendId)?.let { history ->
            _uiState.update { it.copy(quizHistory = history) }
        }
    }

    fun selectCharacter(characterId: String) {
        if (!shouldResetActivationAttempt(_uiState.value.selectedCharacterId, characterId)) return
        if (_uiState.value.characters.none { it.id == characterId }) return
        _uiState.update {
            it.copy(
                selectedCharacterId = characterId,
                activationState = if (it.activatedCharacterId == characterId) AiActivationState.SUCCESS else AiActivationState.IDLE,
                activationMessage = null,
                errorCode = null,
                // 换角色重置失败尝试计数，避免某角色语音激活 3 次失败后永久锁死所有角色
                activationAttempt = 0
            )
        }
        scheduleCharacterPreview()
    }

    fun activate(context: Context) {
        val state = _uiState.value
        if (!state.isAuthorized) {
            setError("AUTH_REQUIRED")
            return
        }
        val character = state.selectedCharacter ?: return
        if (!character.isAvailable) {
            setError("CHARACTER_UNAVAILABLE")
            return
        }
        when (activationRequestMode(state.activationAttempt)) {
            AiActivationRequestMode.TEXT -> {
                activateByText()
                return
            }
            AiActivationRequestMode.NONE -> {
                setError("ACTIVATION_RETRY_LIMIT")
                return
            }
            AiActivationRequestMode.VOICE -> Unit
        }
        if (!canActivateCharacter(character, state) && state.activationAttempt > 0) {
            setError("ACTIVATION_RETRY_LIMIT")
            return
        }
        if (!canActivateCharacter(character, state)) {
            setError("ACTIVATION_UNAVAILABLE")
            return
        }
        val attempt = state.activationAttempt + 1
        _uiState.update {
            it.copy(
                activationAttempt = attempt,
                activationState = AiActivationState.RECORDING,
                activationMessage = null,
                errorCode = null
            )
        }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            val audioDataUrl = AiAudioRecorder.recordOnce(context)
            if (audioDataUrl.isNullOrBlank()) {
                _uiState.update { it.copy(activationState = AiActivationState.FAILED, errorCode = "AUDIO_UNAVAILABLE") }
                return@launch
            }
            _uiState.update { it.copy(activationState = AiActivationState.VERIFYING) }
            aiRepository.activate(
                friendId = authManager.friendId.value.orEmpty(),
                request = AiActivateRequest(
                    characterId = character.id,
                    audioDataUrl = audioDataUrl,
                    spokenName = character.activationWord,
                    sessionId = spriteSessionId
                )
            ).onSuccess { activation ->
                _uiState.update {
                    it.copy(
                        activatedCharacterId = if (activation.activated) character.id else it.activatedCharacterId,
                        activationState = if (activation.activated) AiActivationState.SUCCESS else AiActivationState.FAILED,
                        activationMessage = activation.activationPhrase.takeIf { activation.activated && it.isNotBlank() },
                        quota = activation.quota ?: it.quota,
                        errorCode = if (activation.activated) null else "ACTIVATION_NOT_MATCHED"
                    )
                }
                activation.audio?.let { _audioEvents.emit(it) }
                if (activation.activated) loadGreeting(character.id)
            }.onFailure { error ->
                _uiState.update { it.copy(activationState = AiActivationState.FAILED, errorCode = errorCode(error)) }
            }
        }
    }

    /** 麦克风权限被拒绝或设备没有输入源时，提供一次明确的文字兜底。 */
    fun activateByText() {
        val state = _uiState.value
        if (!state.isAuthorized) {
            setError("AUTH_REQUIRED")
            return
        }
        val character = state.selectedCharacter ?: return
        if (!character.isAvailable) {
            setError("CHARACTER_UNAVAILABLE")
            return
        }
        if (activationRequestMode(state.activationAttempt) == AiActivationRequestMode.VOICE) {
            setError("VOICE_ACTIVATION_REQUIRED")
            return
        }
        if (!canActivateCharacter(character, state)) {
            setError("ACTIVATION_RETRY_LIMIT")
            return
        }
        val attempt = state.activationAttempt + 1
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    activationAttempt = attempt,
                    activationState = AiActivationState.VERIFYING,
                    errorCode = null
                )
            }
            aiRepository.activate(
                authManager.friendId.value.orEmpty(),
                AiActivateRequest(
                    characterId = character.id,
                    spokenName = character.activationWord,
                    sessionId = spriteSessionId
                )
            ).onSuccess { activation ->
                _uiState.update {
                    it.copy(
                        activatedCharacterId = if (activation.activated) character.id else it.activatedCharacterId,
                        activationState = if (activation.activated) AiActivationState.SUCCESS else AiActivationState.FAILED,
                        activationMessage = activation.activationPhrase.takeIf { activation.activated && it.isNotBlank() },
                        quota = activation.quota ?: it.quota,
                        errorCode = if (activation.activated) null else "ACTIVATION_NOT_MATCHED"
                    )
                }
                activation.audio?.let { _audioEvents.emit(it) }
                if (activation.activated) loadGreeting(character.id)
            }.onFailure { error ->
                _uiState.update { it.copy(activationState = AiActivationState.FAILED, errorCode = errorCode(error)) }
            }
        }
    }

    fun openFeature(feature: AiFeature) {
        _uiState.update { it.copy(activeFeature = feature, errorCode = null) }
        when (feature) {
            AiFeature.GREETING -> loadGreeting(_uiState.value.activatedCharacterId ?: _uiState.value.selectedCharacterId)
            AiFeature.TASTE -> loadTaste()
            AiFeature.QUIZ -> prepareQuizPreview()
            AiFeature.DAILY -> loadDaily()
        }
    }

    fun closeFeature() {
        invalidateCurrentRequest()
        _uiState.update {
            it.copy(
                isLoading = false,
                activeFeature = null,
                loadingFeature = null,
                errorCode = null
            )
        }
    }

    fun refreshFeature() {
        when (_uiState.value.activeFeature) {
            AiFeature.GREETING -> loadGreeting(
                _uiState.value.activatedCharacterId ?: _uiState.value.selectedCharacterId,
                forceRefresh = true
            )
            AiFeature.TASTE -> loadTaste(forceRefresh = true)
            AiFeature.QUIZ -> prepareQuizPreview()
            AiFeature.DAILY -> loadDaily(forceRefresh = true)
            null -> Unit
        }
    }

    fun setQuizAnswer(questionId: String, optionIds: List<String>, textAnswer: String? = null) {
        _uiState.update { state ->
            state.copy(
                quizAnswers = state.quizAnswers + (questionId to AiQuizAnswer(questionId, optionIds, textAnswer))
            )
        }
    }

    fun setQuizTextAnswer(questionId: String, textAnswer: String) {
        val current = _uiState.value.quizAnswers[questionId]
        setQuizAnswer(questionId, current?.selectedOptionIds.orEmpty(), textAnswer)
    }

    fun nextQuestion() {
        _uiState.update { state ->
            val lastIndex = (state.quiz?.questions?.size ?: 1) - 1
            state.copy(quizIndex = (state.quizIndex + 1).coerceAtMost(lastIndex))
        }
    }

    fun previousQuestion() {
        _uiState.update { it.copy(quizIndex = (it.quizIndex - 1).coerceAtLeast(0)) }
    }

    fun submitQuiz() {
        val state = _uiState.value
        val quiz = state.quiz?.takeIf { state.quizStarted } ?: return
        beginRequest()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorCode = null) }
            aiRepository.submitQuiz(
                authManager.friendId.value.orEmpty(),
                quiz.quizId,
                state.quizAnswers.values.toList()
            ).onSuccess { result ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        quizResult = result,
                        quizResultRevision = it.quizResultRevision + 1L,
                        quota = result.quota ?: it.quota
                    )
                }
                // 保存最近/最高成绩与错题（离线可浏览闯关历史）
                val friendId = authManager.friendId.value.orEmpty()
                if (friendId.isNotBlank()) {
                    aiRepository.saveQuizHistory(friendId, result)
                    aiRepository.readQuizHistory(friendId)?.let { history ->
                        _uiState.update { it.copy(quizHistory = history) }
                    }
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isLoading = false, errorCode = errorCode(error)) }
            }
        }
    }

    fun replayQuiz() {
        _uiState.update {
            it.copy(
                quiz = null,
                quizResult = null,
                quizStarted = false,
                quizIndex = 0,
                quizAnswers = emptyMap(),
                quizReplacementCount = 0,
                quizPreviewMovies = emptyList()
            )
        }
        quizCandidates = emptyList()
        prepareQuizPreview()
    }

    fun startQuiz() {
        val preview = _uiState.value.quizPreviewMovies
        if (preview.size < 7) {
            setError("NOT_ENOUGH_MOVIES")
            return
        }
        loadQuiz(preview, forceRefresh = false)
    }

    fun replaceQuizMovie(index: Int) {
        val state = _uiState.value
        val next = replaceQuizPreview(
            current = state.quizPreviewMovies,
            candidates = quizCandidates,
            index = index,
            replacementCount = state.quizReplacementCount
        )
        if (next != state.quizPreviewMovies) {
            _uiState.update {
                it.copy(
                    quizPreviewMovies = next,
                    quizReplacementCount = it.quizReplacementCount + 1
                )
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorCode = null) }
    }

    private suspend fun loadCharacters() {
        aiRepository.listCharacters().onSuccess { remote ->
            val remoteById = remote.associateBy { it.id }
            _uiState.update { state ->
                state.copy(characters = state.characters.map { local -> remoteById[local.id] ?: local })
            }
        }
    }

    /** 试听请求挂在本预览 Job 内执行：取消 previewJob 会一并取消 TTS，避免快速切换角色时旧请求后完成、播放上一个角色的声音。 */
    private suspend fun previewSelectedCharacter() {
        val character = _uiState.value.selectedCharacter ?: return
        val authorized = isAuthorized()
        val request = buildAuditionTtsRequest(character, spriteSessionId)
        when (auditionPlaybackRoute(authorized, character.isAvailable)) {
            AiAuditionPlaybackRoute.SYSTEM_TTS -> {
                if (!authorized) emitGuestPreviewFallback(character)
            }
            AiAuditionPlaybackRoute.GUEST_TTS -> {
                aiRepository.playGuestTts(request).fold(
                    onSuccess = { audio ->
                        if (audio.hasPlayableSource()) _audioEvents.emit(audio)
                        else emitGuestPreviewFallback(character)
                    },
                    onFailure = { emitGuestPreviewFallback(character) }
                )
            }
            AiAuditionPlaybackRoute.AUTHORIZED_TTS -> {
                aiRepository.playTts(authManager.friendId.value.orEmpty(), request)
                    .onSuccess { audio ->
                        if (audio.hasPlayableSource()) _audioEvents.emit(audio)
                    }
            }
        }
    }

    private fun scheduleCharacterPreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(350)
            previewSelectedCharacter()
        }
    }

    private suspend fun emitGuestPreviewFallback(character: AiCharacter) {
        character.auditionText.takeIf { it.isNotBlank() }?.let { text ->
            _guestPreviewFallbackEvents.emit(text)
        }
    }

    private fun AiAudio.hasPlayableSource(): Boolean =
        !audioDataUrl.isNullOrBlank() || !audioUrl.isNullOrBlank()

    private fun loadGreeting(characterId: String, forceRefresh: Boolean = false) {
        runFeature(AiFeature.GREETING) {
            aiRepository.getGreeting(authManager.friendId.value.orEmpty(), characterId, forceRefresh)
                .onSuccess { greeting ->
                    _uiState.update { it.copy(greeting = greeting, quota = greeting.quota ?: it.quota) }
                    greeting.audio?.let { _audioEvents.emit(it) }
                }
                .getOrElse { throw it }
        }
    }

    private fun loadTaste(forceRefresh: Boolean = false) {
        runFeature(AiFeature.TASTE) {
            val watched = watchedTitles()
            if (watched.isEmpty()) throw IllegalStateException("WATCHED_LIST_EMPTY")
            aiRepository.getTaste(
                authManager.friendId.value.orEmpty(),
                com.tracktosearch.data.ai.AiTasteRequest(watched = watched, forceRefresh = forceRefresh),
                forceRefresh
            ).onSuccess { taste ->
                _uiState.update {
                    it.copy(
                        taste = taste,
                        tasteRevision = it.tasteRevision + 1L,
                        quota = taste.quota ?: it.quota
                    )
                }
            }
                .getOrElse { throw it }
        }
    }

    private fun prepareQuizPreview() {
        runFeature(AiFeature.QUIZ) {
            val watched = watchedTitles()
            if (watched.size < 7) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            quizCandidates = watched
            _uiState.update {
                it.copy(
                    quiz = null,
                    quizResult = null,
                    quizStarted = false,
                    quizIndex = 0,
                    quizAnswers = emptyMap(),
                    quizPreviewMovies = selectQuizPreview(watched),
                    quizReplacementCount = 0
                )
            }
        }
    }

    private fun loadQuiz(watched: List<AiWatchedTitleDto>, forceRefresh: Boolean) {
        runFeature(AiFeature.QUIZ) {
            if (watched.size < 7) throw IllegalStateException("NOT_ENOUGH_MOVIES")
            aiRepository.getQuiz(
                authManager.friendId.value.orEmpty(),
                com.tracktosearch.data.ai.AiQuizRequest(
                    watched = watched,
                    excludedQuizIds = recentQuizIds,
                    questionCount = 13,
                    sessionId = spriteSessionId
                ),
                forceRefresh
            ).onSuccess { quiz ->
                recentQuizIds = (listOf(quiz.quizId) + recentQuizIds).take(3)
                _uiState.update {
                    it.copy(
                        quiz = quiz,
                        quizStarted = true,
                        quizIndex = 0,
                        quizAnswers = emptyMap(),
                        quizResult = null,
                        quota = quiz.quota ?: it.quota
                    )
                }
            }.getOrElse { throw it }
        }
    }

    private fun loadDaily(forceRefresh: Boolean = false) {
        runFeature(AiFeature.DAILY) {
            aiRepository.getDailyKnowledge(authManager.friendId.value.orEmpty(), forceRefresh)
                .onSuccess { daily ->
                    _uiState.update {
                        it.copy(dailyKnowledge = daily, quota = daily.quota ?: it.quota)
                    }
                }
                .getOrElse { throw it }
        }
    }

    private fun runFeature(feature: AiFeature, block: suspend () -> Unit) {
        if (!isAuthorized()) {
            setError("AUTH_REQUIRED")
            return
        }
        val requestId = beginRequest()
        requestJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingFeature = feature, errorCode = null) }
            try {
                block()
            } catch (e: CancellationException) {
                // 新请求（切换功能/提交/激活）取消旧请求时，不能走 onFailure 画假错误或翻转状态
                throw e
            } catch (e: Exception) {
                if (requestGeneration == requestId) {
                    _uiState.update { it.copy(errorCode = errorCode(e)) }
                }
            } finally {
                if (requestGeneration == requestId) {
                    _uiState.update { it.copy(isLoading = false, loadingFeature = null) }
                }
            }
        }
    }

    private fun beginRequest(): Long {
        invalidateCurrentRequest()
        return requestGeneration
    }

    private fun invalidateCurrentRequest() {
        requestGeneration += 1L
        requestJob?.cancel()
        requestJob = null
    }

    private suspend fun watchedTitles(): List<AiWatchedTitleDto> = withContext(Dispatchers.IO) {
        val results = listOf(
            async { traktRepository.getAllMovieHistory(extended = "full") },
            async { traktRepository.getAllShowHistory(extended = "full") }
        ).awaitAll()
        val movies = (results[0].getOrNull() as? List<TraktWatchlistMovieItem>).orEmpty().map { it.toAiWatched() }
        val shows = (results[1].getOrNull() as? List<TraktWatchlistShowItem>).orEmpty().map { it.toAiWatched() }
        (movies + shows).sortedByDescending { it.watchedAt.orEmpty() }.take(60)
    }

    private fun isAuthorized(): Boolean =
        _uiState.value.isAuthorized && !authManager.friendId.value.isNullOrBlank()

    private fun setError(code: String) {
        _uiState.update { it.copy(errorCode = code) }
    }

    private fun errorCode(error: Throwable): String {
        val aiError = error as? com.tracktosearch.data.ai.AiApiException
        return aiError?.errorCode?.name ?: error.message?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
    }
}

private fun TraktWatchlistMovieItem.toAiWatched(): AiWatchedTitleDto = AiWatchedTitleDto(
    mediaId = movie.ids.trakt.takeIf { it > 0 }?.toString() ?: movie.ids.tmdb.toString(),
    mediaType = "movie",
    title = movie.title,
    year = movie.year.takeIf { it > 0 },
    genres = movie.genres,
    publicRating = movie.rating,
    watchedAt = watched_at.takeIf { it.isNotBlank() },
    mediaIds = AiMediaIdsDto(
        traktId = movie.ids.trakt.takeIf { it > 0 }?.toString(),
        tmdbId = movie.ids.tmdb.takeIf { it > 0 },
        imdbId = movie.ids.imdb.takeIf { it.isNotBlank() }
    )
)

private fun TraktWatchlistShowItem.toAiWatched(): AiWatchedTitleDto = AiWatchedTitleDto(
    mediaId = show.ids.trakt.takeIf { it > 0 }?.toString() ?: show.ids.tmdb.toString(),
    mediaType = "show",
    title = show.title,
    year = show.year.takeIf { it > 0 },
    genres = show.genres,
    publicRating = show.rating,
    watchedAt = watched_at.takeIf { it.isNotBlank() },
    mediaIds = AiMediaIdsDto(
        traktId = show.ids.trakt.takeIf { it > 0 }?.toString(),
        tmdbId = show.ids.tmdb.takeIf { it > 0 },
        imdbId = show.ids.imdb.takeIf { it.isNotBlank() }
    )
)
