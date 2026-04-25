package at.aau.se2.skyjo.game.service

import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import org.springframework.stereotype.Service

@Service
class SkyjoGameService(
    private val engine: SkyjoEngine,
) {
    private var currentState: GameState = GameState()

    @Synchronized
    fun startGame(
        playerIds: List<String>,
        initialReveals: Map<String, Set<BoardPosition>>,
        seed: Long? = null,
    ): GameState {
        currentState = engine.startGame(
            playerIds = playerIds,
            initialReveals = initialReveals,
            seed = seed,
        )
        return currentState
    }

    @Synchronized
    fun getGameState(): GameState? = currentState.takeUnless { it.phase == GamePhase.NOT_STARTED }

    @Synchronized
    fun drawFromDeck(): GameState {
        currentState = engine.drawFromDeck(currentState)
        return currentState
    }

    @Synchronized
    fun takeDiscardCard(): GameState {
        currentState = engine.takeDiscardCard(currentState)
        return currentState
    }

    @Synchronized
    fun replaceDrawnCard(position: BoardPosition): GameState {
        currentState = engine.replaceDrawnCard(currentState, position)
        return currentState
    }

    @Synchronized
    fun discardDrawnCardAndReveal(position: BoardPosition): GameState {
        currentState = engine.discardDrawnCardAndReveal(currentState, position)
        return currentState
    }
}
