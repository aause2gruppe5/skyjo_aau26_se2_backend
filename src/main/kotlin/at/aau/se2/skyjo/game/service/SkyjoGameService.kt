package at.aau.se2.skyjo.game.service

import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.persistence.GameRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SkyjoGameService(
    private val engine: SkyjoEngine,
    private val gameRepository: GameRepository?,
) {
    private var currentGameId: String? = null
    private var currentState: GameState = GameState()

    fun getActiveGameId(): String? = currentGameId

    init {
        gameRepository?.loadActiveGame()?.let { (id, state) ->
            currentGameId = id
            currentState = state
        }
    }

    @Synchronized
    fun startGame(
        playerIds: List<String>,
        initialReveals: Map<String, Set<BoardPosition>>,
        seed: Long? = null,
    ): GameState {
        currentGameId = UUID.randomUUID().toString()
        currentState = engine.startGame(
            playerIds = playerIds,
            initialReveals = initialReveals,
            seed = seed,
        )
        gameRepository?.saveGame(currentGameId!!, currentState)
        return currentState
    }

    @Synchronized
    fun getGameState(): GameState? = currentState.takeUnless { it.phase == GamePhase.NOT_STARTED }

    @Synchronized
    fun drawFromDeck(): GameState {
        currentState = engine.drawFromDeck(currentState)
        gameRepository?.saveGame(currentGameId ?: return currentState, currentState)
        return currentState
    }

    @Synchronized
    fun takeDiscardCard(): GameState {
        currentState = engine.takeDiscardCard(currentState)
        gameRepository?.saveGame(currentGameId ?: return currentState, currentState)
        return currentState
    }

    @Synchronized
    fun drawVisibleActionCard(actionCardIndex: Int): GameState {
        currentState = engine.drawVisibleActionCard(currentState, actionCardIndex)
        return currentState
    }

    @Synchronized
    fun drawActionCardFromDeck(): GameState {
        currentState = engine.drawActionCardFromDeck(currentState)
        return currentState
    }

    @Synchronized
    fun discardActionCard(actionCardIndex: Int): GameState {
        currentState = engine.discardActionCard(currentState, actionCardIndex)
        return currentState
    }

    @Synchronized
    fun playActionCard(command: PlayActionCardCommand): GameState {
        currentState = engine.playActionCard(currentState, command)
        return currentState
    }

    @Synchronized
    fun replaceDrawnCard(position: BoardPosition): GameState {
        currentState = engine.replaceDrawnCard(currentState, position)
        gameRepository?.saveGame(currentGameId ?: return currentState, currentState)
        return currentState
    }

    @Synchronized
    fun discardDrawnCardAndReveal(position: BoardPosition): GameState {
        currentState = engine.discardDrawnCardAndReveal(currentState, position)
        gameRepository?.saveGame(currentGameId ?: return currentState, currentState)
        return currentState
    }
}
