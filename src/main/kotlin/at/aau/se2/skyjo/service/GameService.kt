package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.error.InvalidMoveException
import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.ActionCardResult
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.model.displayLabel
import at.aau.se2.skyjo.game.model.scoreValue
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.ActionCardDto
import at.aau.se2.skyjo.model.ActionCardKind
import at.aau.se2.skyjo.model.ActionCardResultMessage
import at.aau.se2.skyjo.model.ActionCardResultType
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.BoardSlotDto
import at.aau.se2.skyjo.model.CardDto
import at.aau.se2.skyjo.model.CardType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.InspectedCardDto
import at.aau.se2.skyjo.model.PlayerBoardDto
import at.aau.se2.skyjo.model.PlayerScoreDto
import at.aau.se2.skyjo.model.PlayActionCardMessageResult
import at.aau.se2.skyjo.model.SlotType
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import at.aau.se2.skyjo.persistence.GameRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class GameService @Autowired constructor(
    private val engine: SkyjoEngine,
    private val gameRepository: GameRepository?,
    private val statsService: StatsService?,
) {

    private data class ManagedGame(
        val gameId: String,
        val lobbyId: String?,
        var gameState: GameState,
        var config: GameConfig,
        var roundNumber: Int,
        var totalScores: Map<String, Int>,
        var playerInfo: Map<String, String>,
        val sessionAliases: MutableMap<String, String> = mutableMapOf(),
        val disconnectedNicknames: MutableSet<String> = mutableSetOf(),
    )

    private val lock = ReentrantLock()
    private val games = mutableMapOf<String, ManagedGame>()
    private val playerGameIndex = mutableMapOf<String, String>()
    private val recordedStatsGameIds = mutableSetOf<String>()

    private var currentGameId: String? = null
    private var gameState: GameState? = null
    private var config: GameConfig = GameConfig()
    private var roundNumber: Int = 0
    private var totalScores: Map<String, Int> = emptyMap()
    private var playerInfo: Map<String, String> = emptyMap()
    private val sessionAliases: MutableMap<String, String> = mutableMapOf()
    private val disconnectedNicknames: MutableSet<String> = mutableSetOf()

    constructor(engine: SkyjoEngine, gameRepository: GameRepository?) : this(engine, gameRepository, null)

    init {
        gameRepository?.loadActiveGames()?.forEach { persisted ->
            val playerIds = persisted.state.players.map { it.id }
            val managedGame = ManagedGame(
                gameId = persisted.gameId,
                lobbyId = persisted.lobbyId,
                gameState = persisted.state,
                config = GameConfig(),
                roundNumber = 1,
                totalScores = playerIds.associateWith { 0 },
                playerInfo = playerIds.associateWith { it },
            )
            games[managedGame.gameId] = managedGame
            playerIds.forEach { playerGameIndex[it] = managedGame.gameId }
            if (currentGameId == null) {
                syncLegacyFrom(managedGame)
            }
        }
    }

    fun getActiveGameId(): String? = lock.withLock { currentGameId }

    fun markPlayerDisconnected(principalId: String) = lock.withLock {
        val game = findManagedGameForPlayer(principalId) ?: return@withLock
        val playerId = game.sessionAliases[principalId] ?: principalId
        val nickname = game.playerInfo[playerId] ?: return@withLock
        game.disconnectedNicknames.add(nickname)
        gameRepository?.markDisconnected(playerId)
        syncLegacyIfCurrent(game)
    }

    fun addSessionAlias(newSessionId: String, nickname: String): Boolean = lock.withLock {
        val game = games.values.firstOrNull { managed ->
            managed.playerInfo.values.any { it == nickname }
        } ?: return@withLock false
        val oldPlayerId = game.playerInfo.entries.first { it.value == nickname }.key

        game.sessionAliases[newSessionId] = oldPlayerId
        playerGameIndex[newSessionId] = game.gameId
        game.disconnectedNicknames.remove(nickname)
        syncLegacyIfCurrent(game)
        true
    }

    fun startGame(players: List<LobbyPlayer>, gameConfig: GameConfig = GameConfig()): GameUpdateMessage = lock.withLock {
        startGameInternal(lobbyId = null, players = players, gameConfig = gameConfig)
    }

    fun startGame(lobbyId: String, players: List<LobbyPlayer>, gameConfig: GameConfig = GameConfig()): GameUpdateMessage =
        lock.withLock {
            startGameInternal(lobbyId = lobbyId, players = players, gameConfig = gameConfig)
        }

    fun processAction(playerId: String, action: GameActionMessage): GameUpdateMessage = lock.withLock {
        val game = findManagedGameForPlayer(playerId) ?: error("game has not started yet")
        syncManagedFromLegacyIfCurrent(game)
        val state = game.gameState
        val resolvedPlayerId = game.sessionAliases[playerId] ?: playerId

        if (state.currentPlayerId != resolvedPlayerId) {
            error("not your turn (current player: ${state.currentPlayerId})")
        }

        val updatedState = when (action.type) {
            ActionType.DRAW -> {
                when (action.source ?: error("source required for DRAW action")) {
                    DrawSource.DECK -> engine.drawFromDeck(state)
                    DrawSource.DISCARD -> engine.takeDiscardCard(state)
                    DrawSource.ACTION_DECK -> engine.drawActionCardFromDeck(state)
                }
            }
            ActionType.DRAW_VISIBLE_ACTION_CARD -> {
                val actionCardIndex = action.actionCardIndex
                    ?: error("actionCardIndex required for DRAW_VISIBLE_ACTION_CARD action")
                engine.drawVisibleActionCard(state, actionCardIndex)
            }
            ActionType.REPLACE -> {
                val row = action.row ?: error("row required for REPLACE action")
                val col = action.col ?: error("col required for REPLACE action")
                engine.replaceDrawnCard(state, BoardPosition(row, col))
            }
            ActionType.DISCARD_AND_REVEAL -> {
                val row = action.row ?: error("row required for DISCARD_AND_REVEAL action")
                val col = action.col ?: error("col required for DISCARD_AND_REVEAL action")
                engine.discardDrawnCardAndReveal(state, BoardPosition(row, col))
            }
            ActionType.PLAY_ACTION_CARD -> {
                val index = action.actionCardIndex ?: error("actionCardIndex required for PLAY_ACTION_CARD action")
                val actionCard = state.currentPlayer().actionCards.getOrNull(index)
                    ?: error("action card index $index is not available")
                val parameters = when (actionCard) {
                    is SkyjoCard.ActionCard.PlayerSwapCard -> action.toPlayerSwapParameters()
                    is SkyjoCard.ActionCard.SwapOwnCards -> action.toSwapOwnParameters()
                    is SkyjoCard.ActionCard.Defense,
                    is SkyjoCard.ActionCard.DoubleTurn,
                    is SkyjoCard.ActionCard.Placeholder -> ActionCardParameters.None
                    is SkyjoCard.ActionCard.Enlightenment ->
                        error("enlightenment requires private PLAY_ACTION_CARD command parameters")
                }

                engine.playActionCard(
                    state,
                    PlayActionCardCommand(
                        actionCardIndex = index,
                        parameters = parameters,
                    ),
                )
            }
            ActionType.DISCARD_ACTION_CARD -> {
                val index = action.actionCardIndex ?: error("actionCardIndex required for DISCARD_ACTION_CARD action")
                engine.discardActionCard(state, index)
            }
        }

        game.gameState = updatedState
        gameRepository?.saveGame(game.gameId, game.lobbyId, updatedState)
        syncLegacyIfCurrent(game)

        if (updatedState.phase == GamePhase.ROUND_FINISHED) {
            return@withLock handleRoundFinished(game, updatedState)
        }

        toUpdateMessage(game, updatedState, gameOver = false)
    }

    fun playActionCard(playerId: String, command: PlayActionCardCommand): PlayActionCardMessageResult = lock.withLock {
        val game = findManagedGameForPlayer(playerId) ?: error("game has not started yet")
        syncManagedFromLegacyIfCurrent(game)
        val state = game.gameState
        val resolvedPlayerId = game.sessionAliases[playerId] ?: playerId

        if (state.currentPlayerId != resolvedPlayerId) {
            error("not your turn (current player: ${state.currentPlayerId})")
        }

        val updatedStateWithResult = engine.playActionCard(state, command)
        val privateResults = updatedStateWithResult.actionCardResult
            ?.let { result -> mapOf(playerId to result.toMessage(command.actionCardIndex)) }
            ?: emptyMap()
        val updatedState = updatedStateWithResult.copy(actionCardResult = null)

        game.gameState = updatedState
        gameRepository?.saveGame(game.gameId, game.lobbyId, updatedState)
        syncLegacyIfCurrent(game)

        val update = if (updatedState.phase == GamePhase.ROUND_FINISHED) {
            handleRoundFinished(game, updatedState)
        } else {
            toUpdateMessage(game, updatedState, gameOver = false)
        }

        PlayActionCardMessageResult(
            gameUpdate = update,
            privateActionCardResults = privateResults,
        )
    }

    fun getCurrentState(): GameUpdateMessage? = lock.withLock {
        currentManagedGame()?.let { game ->
            syncManagedFromLegacyIfCurrent(game)
            toUpdateMessage(game, game.gameState, gameOver = false)
        }
            ?: gameState?.let { toUpdateMessageFromLegacy(it, gameOver = false) }
    }

    fun getCurrentState(playerId: String): GameUpdateMessage? = lock.withLock {
        findManagedGameForPlayer(playerId)?.let { game ->
            syncManagedFromLegacyIfCurrent(game)
            toUpdateMessage(game, game.gameState, gameOver = false)
        }
    }

    internal fun handleRoundFinished(finishedState: GameState): GameUpdateMessage {
        val game = currentManagedGame() ?: error("game has not started yet")
        syncManagedFromLegacyIfCurrent(game)
        return handleRoundFinished(game, finishedState)
    }

    private fun startGameInternal(
        lobbyId: String?,
        players: List<LobbyPlayer>,
        gameConfig: GameConfig,
    ): GameUpdateMessage {
        val playerIds = players.map { it.userId }
        val initialReveals = playerIds.associateWith { setOf(BoardPosition(0, 0), BoardPosition(0, 1)) }
        val newState = engine.startGame(playerIds, initialReveals)
        val newGameId = UUID.randomUUID().toString()
        val managedGame = ManagedGame(
            gameId = newGameId,
            lobbyId = lobbyId,
            gameState = newState,
            config = gameConfig,
            roundNumber = 1,
            totalScores = playerIds.associateWith { 0 },
            playerInfo = players.associate { it.userId to it.nickname },
        )

        players.forEach { player ->
            if (player.sessionId != player.userId) {
                managedGame.sessionAliases[player.sessionId] = player.userId
                playerGameIndex[player.sessionId] = managedGame.gameId
            }
            playerGameIndex[player.userId] = managedGame.gameId
            gameRepository?.savePlayerSession(player.userId, managedGame.gameId, connected = true)
        }

        games[managedGame.gameId] = managedGame
        gameRepository?.saveGame(managedGame.gameId, managedGame.lobbyId, newState)
        syncLegacyFrom(managedGame)
        return toUpdateMessage(managedGame, newState, gameOver = false)
    }

    private fun handleRoundFinished(game: ManagedGame, finishedState: GameState): GameUpdateMessage {
        val roundResult = finishedState.roundResult!!
        game.gameState = finishedState

        roundResult.scores.forEach { score ->
            game.totalScores = game.totalScores + (score.playerId to ((game.totalScores[score.playerId] ?: 0) + score.finalScore))
        }

        val isGameOver = game.roundNumber >= game.config.maxRounds ||
            game.totalScores.values.any { it >= game.config.targetScore }

        if (isGameOver) {
            gameRepository?.saveGame(game.gameId, game.lobbyId, finishedState)
            recordFinalStatsOnce(game)
            syncLegacyIfCurrent(game)
            return toUpdateMessage(game, finishedState, gameOver = true)
        }

        game.roundNumber++
        val playerIds = finishedState.players.map { it.id }
        val initialReveals = playerIds.associateWith { setOf(BoardPosition(0, 0), BoardPosition(0, 1)) }
        val newRoundState = engine.startGame(playerIds, initialReveals)
        game.gameState = newRoundState
        gameRepository?.saveGame(game.gameId, game.lobbyId, newRoundState)
        syncLegacyIfCurrent(game)
        return toUpdateMessage(game, newRoundState, gameOver = false)
    }

    private fun currentManagedGame(): ManagedGame? =
        currentGameId?.let(games::get) ?: ensureLegacyManagedGame()

    private fun findManagedGameForPlayer(principalId: String): ManagedGame? {
        playerGameIndex[principalId]?.let { gameId ->
            games[gameId]?.let { return it }
        }

        val matched = games.values.firstOrNull { game ->
            principalId in game.playerInfo || principalId in game.sessionAliases
        }
        if (matched != null) {
            playerGameIndex[principalId] = matched.gameId
            return matched
        }

        val legacyGame = ensureLegacyManagedGame() ?: return null
        val resolvedPlayerId = legacyGame.sessionAliases[principalId] ?: principalId
        return if (resolvedPlayerId in legacyGame.playerInfo) {
            playerGameIndex[principalId] = legacyGame.gameId
            legacyGame
        } else {
            null
        }
    }

    private fun ensureLegacyManagedGame(): ManagedGame? {
        val state = gameState ?: return null
        val legacyGameId = currentGameId ?: "legacy-game"
        currentGameId = legacyGameId
        val playerIds = state.players.map { it.id }
        val managedGame = games.getOrPut(legacyGameId) {
            ManagedGame(
                gameId = legacyGameId,
                lobbyId = null,
                gameState = state,
                config = config,
                roundNumber = roundNumber.takeIf { it > 0 } ?: 1,
                totalScores = totalScores.ifEmpty { playerIds.associateWith { 0 } },
                playerInfo = playerInfo.ifEmpty { playerIds.associateWith { it } },
                sessionAliases = sessionAliases.toMutableMap(),
                disconnectedNicknames = disconnectedNicknames.toMutableSet(),
            )
        }
        playerIds.forEach { playerGameIndex.putIfAbsent(it, managedGame.gameId) }
        syncManagedFromLegacyIfCurrent(managedGame)
        return managedGame
    }

    private fun syncLegacyFrom(game: ManagedGame) {
        currentGameId = game.gameId
        gameState = game.gameState
        config = game.config
        roundNumber = game.roundNumber
        totalScores = game.totalScores
        playerInfo = game.playerInfo
        sessionAliases.clear()
        sessionAliases.putAll(game.sessionAliases)
        disconnectedNicknames.clear()
        disconnectedNicknames.addAll(game.disconnectedNicknames)
    }

    private fun syncManagedFromLegacyIfCurrent(game: ManagedGame) {
        if (game.gameId != currentGameId) {
            return
        }
        game.gameState = gameState ?: game.gameState
        game.config = config
        game.roundNumber = roundNumber
        game.totalScores = totalScores.ifEmpty { game.totalScores }
        game.playerInfo = playerInfo.ifEmpty { game.playerInfo }
        game.sessionAliases.clear()
        game.sessionAliases.putAll(sessionAliases)
        game.disconnectedNicknames.clear()
        game.disconnectedNicknames.addAll(disconnectedNicknames)
    }

    private fun syncLegacyIfCurrent(game: ManagedGame) {
        if (game.gameId == currentGameId) {
            syncLegacyFrom(game)
        }
    }

    private fun recordFinalStatsOnce(game: ManagedGame) {
        if (recordedStatsGameIds.add(game.gameId)) {
            statsService?.recordGameResult(game.gameId, game.totalScores)
        }
    }

    private fun toUpdateMessage(game: ManagedGame, state: GameState, gameOver: Boolean): GameUpdateMessage {
        val players = state.players.map { playerState ->
            val rows = (0 until BoardLayout.ROWS).map { row ->
                (0 until BoardLayout.COLUMNS).map { col ->
                    val pos = BoardPosition(row, col)
                    when (val slot = playerState.board.slotAt(pos)) {
                        is BoardSlot.Cleared -> BoardSlotDto(type = SlotType.CLEARED, faceUp = null, card = null)
                        is BoardSlot.Occupied -> BoardSlotDto(
                            type = SlotType.OCCUPIED,
                            faceUp = slot.faceUp,
                            card = if (slot.faceUp) toCardDto(slot.card) else null,
                        )
                    }
                }
            }
            PlayerBoardDto(
                playerId = playerState.id,
                nickname = game.playerInfo[playerState.id] ?: playerState.id,
                board = rows,
                actionCards = playerState.actionCards.map(::toActionCardDto),
            )
        }

        val scores = game.totalScores.map { (playerId, score) ->
            PlayerScoreDto(
                playerId = playerId,
                nickname = game.playerInfo[playerId] ?: playerId,
                totalScore = score,
            )
        }

        return GameUpdateMessage(
            phase = state.phase,
            currentPlayerId = state.currentPlayerId,
            players = players,
            discardTopCard = if (state.discardPile.size > 0) toCardDto(state.discardPile.topCard()) else null,
            drawnCard = state.drawnCard?.let { toCardDto(it) },
            visibleActionCards = state.visibleActionCards.map(::toActionCardDto),
            actionDrawPileCount = state.actionDrawPile.size,
            roundResult = state.roundResult,
            roundNumber = game.roundNumber,
            totalScores = scores,
            gameOver = gameOver,
            gameId = game.gameId,
            lobbyId = game.lobbyId,
            disconnectedPlayers = game.disconnectedNicknames.toList(),
        )
    }

    private fun toUpdateMessageFromLegacy(state: GameState, gameOver: Boolean): GameUpdateMessage {
        val legacyGame = ManagedGame(
            gameId = currentGameId ?: "legacy-game",
            lobbyId = null,
            gameState = state,
            config = config,
            roundNumber = roundNumber,
            totalScores = totalScores,
            playerInfo = playerInfo,
            sessionAliases = sessionAliases.toMutableMap(),
            disconnectedNicknames = disconnectedNicknames.toMutableSet(),
        )
        return toUpdateMessage(legacyGame, state, gameOver)
    }

    private fun toCardDto(card: SkyjoCard): CardDto =
        when (card) {
            is SkyjoCard.NumberCard -> CardDto(id = card.id, value = card.value, type = CardType.NUMBER)
            is SkyjoCard.ActionCard -> CardDto(id = card.id, value = card.scoreValue(), type = CardType.ACTION)
        }

    private fun toActionCardDto(card: SkyjoCard.ActionCard): ActionCardDto =
        ActionCardDto(
            id = card.id,
            kind = when (card) {
                is SkyjoCard.ActionCard.Enlightenment -> ActionCardKind.ENLIGHTENMENT
                is SkyjoCard.ActionCard.Placeholder -> ActionCardKind.PLACEHOLDER
                is SkyjoCard.ActionCard.Defense -> ActionCardKind.DEFENSE
                is SkyjoCard.ActionCard.DoubleTurn -> ActionCardKind.DOUBLE_TURN
                is SkyjoCard.ActionCard.SwapOwnCards -> ActionCardKind.SWAP_OWN_CARDS
                is SkyjoCard.ActionCard.PlayerSwapCard -> ActionCardKind.PLAYER_SWAP
            },
            label = card.displayLabel(),
            value = card.scoreValue(),
        )

    private fun GameActionMessage.toSwapOwnParameters(): ActionCardParameters.SwapOwnParameters {
        val p1Row = targetPlayer1Row ?: throw InvalidMoveException("targetPlayer1Row required for PLAY_ACTION_CARD")
        val p1Col = targetPlayer1Col ?: throw InvalidMoveException("targetPlayer1Col required for PLAY_ACTION_CARD")
        val p2Row = targetPlayer2Row ?: throw InvalidMoveException("targetPlayer2Row required for PLAY_ACTION_CARD")
        val p2Col = targetPlayer2Col ?: throw InvalidMoveException("targetPlayer2Col required for PLAY_ACTION_CARD")

        return ActionCardParameters.SwapOwnParameters(
            pos1 = boardPositionOrInvalid(p1Row, p1Col),
            pos2 = boardPositionOrInvalid(p2Row, p2Col),
        )
    }

    private fun boardPositionOrInvalid(row: Int, col: Int): BoardPosition =
        runCatching { BoardPosition(row, col) }
            .getOrElse { throw InvalidMoveException(it.message ?: "board position is not available") }

    private fun ActionCardResult.toMessage(actionCardIndex: Int): ActionCardResultMessage =
        when (this) {
            is ActionCardResult.Enlightenment -> {
                val inspectedCards = cards.map { viewedCard ->
                    InspectedCardDto(
                        row = viewedCard.position.row,
                        col = viewedCard.position.column,
                        value = viewedCard.card?.scoreValue(),
                        card = viewedCard.card?.let(::toCardDto),
                    )
                }
                ActionCardResultMessage(
                    type = ActionCardResultType.ENLIGHTENMENT,
                    actionCardIndex = actionCardIndex,
                    targetPlayerId = targetPlayerId,
                    targetType = targetType,
                    lineIndex = lineIndex,
                    inspectedValues = inspectedCards.map { it.value },
                    inspectedCards = inspectedCards,
                )
            }
        }

    private fun GameActionMessage.toPlayerSwapParameters(): ActionCardParameters.PlayerSwap {
        val p1Id = targetPlayer1Id ?: error("targetPlayer1Id required for PLAY_ACTION_CARD")
        val p1Row = targetPlayer1Row ?: error("targetPlayer1Row required for PLAY_ACTION_CARD")
        val p1Col = targetPlayer1Col ?: error("targetPlayer1Col required for PLAY_ACTION_CARD")
        val p2Id = targetPlayer2Id ?: error("targetPlayer2Id required for PLAY_ACTION_CARD")
        val p2Row = targetPlayer2Row ?: error("targetPlayer2Row required for PLAY_ACTION_CARD")
        val p2Col = targetPlayer2Col ?: error("targetPlayer2Col required for PLAY_ACTION_CARD")

        return ActionCardParameters.PlayerSwap(
            player1Id = p1Id,
            player1Position = BoardPosition(p1Row, p1Col),
            player2Id = p2Id,
            player2Position = BoardPosition(p2Row, p2Col),
        )
    }
}
