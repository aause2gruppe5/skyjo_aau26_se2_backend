package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.model.scoreValue
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.ActionCardDto
import at.aau.se2.skyjo.model.ActionCardKind
import at.aau.se2.skyjo.model.ActionType
import at.aau.se2.skyjo.model.BoardSlotDto
import at.aau.se2.skyjo.model.CardDto
import at.aau.se2.skyjo.model.CardType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameConfig
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.PlayerBoardDto
import at.aau.se2.skyjo.model.PlayerScoreDto
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
                val index = action.actionCardIndex ?: error("actionCardIndex required for DRAW_VISIBLE_ACTION_CARD action")
                engine.drawVisibleActionCard(state, index)
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
                    is SkyjoCard.ActionCard.Defense,
                    is SkyjoCard.ActionCard.DoubleTurn,
                    is SkyjoCard.ActionCard.Placeholder -> ActionCardParameters.None
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

        gameState = updatedState
        currentGameId?.let { gameRepository?.saveGame(it, updatedState) }

        if (updatedState.phase == GamePhase.ROUND_FINISHED) {
            return@withLock handleRoundFinished(updatedState)
        }

        toUpdateMessage(updatedState, gameOver = false)
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
                is SkyjoCard.ActionCard.Placeholder -> ActionCardKind.PLACEHOLDER
                is SkyjoCard.ActionCard.Defense -> ActionCardKind.DEFENSE
                is SkyjoCard.ActionCard.DoubleTurn -> ActionCardKind.DOUBLE_TURN
                is SkyjoCard.ActionCard.PlayerSwapCard -> ActionCardKind.PLAYER_SWAP
            },
        )

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
