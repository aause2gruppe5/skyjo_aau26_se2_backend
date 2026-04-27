package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.model.scoreValue
import at.aau.se2.skyjo.game.service.SkyjoEngine
import at.aau.se2.skyjo.model.BoardSlotDto
import at.aau.se2.skyjo.model.CardDto
import at.aau.se2.skyjo.model.CardType
import at.aau.se2.skyjo.model.GameActionMessage
import at.aau.se2.skyjo.model.GameUpdateMessage
import at.aau.se2.skyjo.model.PlayerBoardDto
import at.aau.se2.skyjo.model.SlotType
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Service
class GameService {

    private val engine = SkyjoEngine()
    private val lock = ReentrantLock()

    private var gameState: GameState? = null
    private var sessionToPlayerId: Map<String, String> = emptyMap()

    fun startGame(players: List<LobbyPlayer>): GameUpdateMessage = lock.withLock {
        val playerIds = players.map { it.sessionId }
        val initialReveals = playerIds.associateWith {
            setOf(BoardPosition(0, 0), BoardPosition(0, 1))
        }
        val newState = engine.startGame(playerIds, initialReveals)
        gameState = newState
        sessionToPlayerId = players.associate { it.sessionId to it.sessionId }
        toUpdateMessage(newState)
    }

    // TODO: implement full action processing
    fun processAction(sessionId: String, @Suppress("UNUSED_PARAMETER") action: GameActionMessage): GameUpdateMessage = lock.withLock {
        val state = gameState ?: error("game has not started yet")
        toUpdateMessage(state)
    }

    fun getCurrentState(): GameUpdateMessage? = lock.withLock {
        gameState?.let { toUpdateMessage(it) }
    }

    private fun toUpdateMessage(state: GameState): GameUpdateMessage {
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
            PlayerBoardDto(playerId = playerState.id, board = rows)
        }

        return GameUpdateMessage(
            phase = state.phase,
            currentPlayerId = state.currentPlayerId,
            players = players,
            discardTopCard = if (state.discardPile.size > 0) toCardDto(state.discardPile.topCard()) else null,
            drawnCard = state.drawnCard?.let { toCardDto(it) },
            roundResult = state.roundResult,
        )
    }

    private fun toCardDto(card: SkyjoCard): CardDto =
        when (card) {
            is SkyjoCard.NumberCard -> CardDto(id = card.id, value = card.value, type = CardType.NUMBER)
            is SkyjoCard.ActionCard -> CardDto(id = card.id, value = card.scoreValue(), type = CardType.ACTION)
        }
}
