package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.model.ActionCardResult
import at.aau.se2.skyjo.game.model.displayLabel
import at.aau.se2.skyjo.game.model.scoreValue
import at.aau.se2.skyjo.model.ActionCardDto
import at.aau.se2.skyjo.model.ActionCardKind
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.ActionCardResultMessage
import at.aau.se2.skyjo.model.ActionCardResultType
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
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class GameService(
    private val engine: SkyjoEngine,
    private val gameRepository: GameRepository?,
) {

    private val lock = ReentrantLock()

    private var currentGameId: String? = null
    private var gameState: GameState? = null
    private var config: GameConfig = GameConfig()
    private var roundNumber: Int = 0
    private var totalScores: Map<String, Int> = emptyMap()
    private var playerInfo: Map<String, String> = emptyMap()
    private val sessionAliases: MutableMap<String, String> = mutableMapOf()
    private val disconnectedNicknames: MutableSet<String> = mutableSetOf()

    fun getActiveGameId(): String? = currentGameId

    fun markPlayerDisconnected(principalId: String) {
        val nickname = playerInfo[principalId] ?: return
        disconnectedNicknames.add(nickname)
        gameRepository?.markDisconnected(nickname)
    }

    fun addSessionAlias(newSessionId: String, nickname: String): Boolean = lock.withLock {
        val oldPlayerId = playerInfo.entries.firstOrNull { it.value == nickname }?.key ?: return@withLock false
        sessionAliases[newSessionId] = oldPlayerId
        disconnectedNicknames.remove(nickname)
        true
    }

    init {
        gameRepository?.loadActiveGame()?.let { (id, state) ->
            currentGameId = id
            gameState = state
        }
    }

    fun startGame(players: List<LobbyPlayer>, gameConfig: GameConfig = GameConfig()): GameUpdateMessage = lock.withLock {
        val playerIds = players.map { it.sessionId }
        val initialReveals = playerIds.associateWith { setOf(BoardPosition(0, 0), BoardPosition(0, 1)) }

        config = gameConfig
        roundNumber = 1
        totalScores = playerIds.associateWith { 0 }
        playerInfo = players.associate { it.sessionId to it.nickname }

        val newState = engine.startGame(playerIds, initialReveals)
        gameState = newState
        currentGameId = UUID.randomUUID().toString()
        gameRepository?.saveGame(currentGameId!!, newState)
        players.forEach { gameRepository?.savePlayerSession(it.nickname, currentGameId!!, connected = true) }
        toUpdateMessage(newState, gameOver = false)
    }

    fun processAction(playerId: String, action: GameActionMessage): GameUpdateMessage = lock.withLock {
        val state = gameState ?: error("game has not started yet")
        val resolvedPlayerId = sessionAliases[playerId] ?: playerId

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
        }

        gameState = updatedState
        currentGameId?.let { gameRepository?.saveGame(it, updatedState) }

        if (updatedState.phase == GamePhase.ROUND_FINISHED) {
            return@withLock handleRoundFinished(updatedState)
        }

        toUpdateMessage(updatedState, gameOver = false)
    }

    fun playActionCard(playerId: String, command: PlayActionCardCommand): PlayActionCardMessageResult = lock.withLock {
        val state = gameState ?: error("game has not started yet")
        val resolvedPlayerId = sessionAliases[playerId] ?: playerId

        if (state.currentPlayerId != resolvedPlayerId) {
            error("not your turn (current player: ${state.currentPlayerId})")
        }

        val updatedStateWithResult = engine.playActionCard(state, command)
        val privateResults = updatedStateWithResult.actionCardResult
            ?.let { result -> mapOf(playerId to result.toMessage(command.actionCardIndex)) }
            ?: emptyMap()
        val updatedState = updatedStateWithResult.copy(actionCardResult = null)

        gameState = updatedState
        currentGameId?.let { gameRepository?.saveGame(it, updatedState) }

        val update = if (updatedState.phase == GamePhase.ROUND_FINISHED) {
            handleRoundFinished(updatedState)
        } else {
            toUpdateMessage(updatedState, gameOver = false)
        }

        PlayActionCardMessageResult(
            gameUpdate = update,
            privateActionCardResults = privateResults,
        )
    }

    fun getCurrentState(): GameUpdateMessage? = lock.withLock {
        gameState?.let { toUpdateMessage(it, gameOver = false) }
    }

    internal fun handleRoundFinished(finishedState: GameState): GameUpdateMessage {
        val roundResult = finishedState.roundResult!!

        roundResult.scores.forEach { score ->
            totalScores = totalScores + (score.playerId to ((totalScores[score.playerId] ?: 0) + score.finalScore))
        }

        val isGameOver = roundNumber >= config.maxRounds || totalScores.values.any { it >= config.targetScore }

        if (isGameOver) {
            return toUpdateMessage(finishedState, gameOver = true)
        }

        roundNumber++
        val playerIds = finishedState.players.map { it.id }
        val initialReveals = playerIds.associateWith { setOf(BoardPosition(0, 0), BoardPosition(0, 1)) }
        val newRoundState = engine.startGame(playerIds, initialReveals)
        gameState = newRoundState
        currentGameId?.let { gameRepository?.saveGame(it, newRoundState) }
        return toUpdateMessage(newRoundState, gameOver = false)
    }

    private fun toUpdateMessage(state: GameState, gameOver: Boolean): GameUpdateMessage {
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
                nickname = playerInfo[playerState.id] ?: playerState.id,
                board = rows,
                actionCards = playerState.actionCards.map(::toActionCardDto),
            )
        }

        val scores = totalScores.map { (playerId, score) ->
            PlayerScoreDto(
                playerId = playerId,
                nickname = playerInfo[playerId] ?: playerId,
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
            roundNumber = roundNumber,
            totalScores = scores,
            gameOver = gameOver,
            gameId = currentGameId,
            disconnectedPlayers = disconnectedNicknames.toList(),
        )
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
            },
            label = card.displayLabel(),
            value = card.scoreValue(),
        )

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
}
