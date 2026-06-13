package at.aau.se2.skyjo.game.service

import at.aau.se2.skyjo.game.error.GameNotStartedException
import at.aau.se2.skyjo.game.error.InvalidGameSetupException
import at.aau.se2.skyjo.game.error.InvalidMoveException
import at.aau.se2.skyjo.game.error.RoundAlreadyFinishedException
import at.aau.se2.skyjo.game.model.ActionDiscardPile
import at.aau.se2.skyjo.game.model.ActionCardResult
import at.aau.se2.skyjo.game.model.ActionCardParameters
import at.aau.se2.skyjo.game.model.ActionDrawPile
import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.DiscardPile
import at.aau.se2.skyjo.game.model.DrawPile
import at.aau.se2.skyjo.game.model.DrawSource
import at.aau.se2.skyjo.game.model.DrawThreeCardsChoiceMode
import at.aau.se2.skyjo.game.model.DrawThreeCardsDiscardReference
import at.aau.se2.skyjo.game.model.GamePhase
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.PendingActionCard
import at.aau.se2.skyjo.game.model.PlayerBoard
import at.aau.se2.skyjo.game.model.PlayerState
import at.aau.se2.skyjo.game.model.PlayActionCardCommand
import at.aau.se2.skyjo.game.model.RoundResult
import at.aau.se2.skyjo.game.model.SkyjoCard
import at.aau.se2.skyjo.game.model.SkyjoDeckFactory
import at.aau.se2.skyjo.game.model.toEffect
import org.springframework.stereotype.Component
import kotlin.random.Random

private const val VISIBLE_ACTION_CARD_COUNT = 4
private const val DRAW_THREE_CARDS_COUNT = 3

@Component
class SkyjoEngine {

    fun startGame(
        playerIds: List<String>,
        initialReveals: Map<String, Set<BoardPosition>>,
        seed: Long? = null,
    ): GameState {
        validateSetup(playerIds, initialReveals)

        var drawPile = SkyjoDeckFactory.createShuffledDrawPile(seed)
        val actionCardSetup = setupActionCards(SkyjoDeckFactory.createShuffledActionDrawPile(seed))
        val players = playerIds.map { playerId ->
            val cards = buildList {
                repeat(BoardLayout.POSITIONS.size) {
                    val drawResult = drawPile.draw()
                    add(drawResult.card)
                    drawPile = drawResult.remainingPile
                }
            }

            PlayerState(
                id = playerId,
                board = PlayerBoard.fromCards(
                    cards = cards,
                    revealedPositions = initialReveals.getValue(playerId),
                ),
            )
        }

        val openingDiscard = drawPile.draw() // one card gets disposed on game start according to rules
        val startingPlayerIndex = determineStartingPlayerIndex(players, initialReveals)

        return GameState(
            players = players,
            currentPlayerIndex = startingPlayerIndex,
            drawPile = openingDiscard.remainingPile,
            discardPile = DiscardPile(listOf(openingDiscard.card)),
            actionDrawPile = actionCardSetup.drawPile,
            visibleActionCards = actionCardSetup.visibleCards,
            actionDiscardPile = actionCardSetup.discardPile,
            phase = GamePhase.AWAITING_DRAW,
            shuffleSeed = seed,
        )
    }

    fun drawFromDeck(state: GameState): GameState {
        val playableState = requireReadyForTurnAction(state, "draw from deck")
        val replenishedState = replenishDrawPileIfNeeded(playableState)
        val drawResult = replenishedState.drawPile.draw()
        return replenishedState.copy(
            drawPile = drawResult.remainingPile,
            drawnCard = drawResult.card,
            drawSource = DrawSource.DECK,
            phase = GamePhase.AWAITING_REPLACEMENT,
            actionCardResult = null,
        )
    }

    fun peekTopDrawCard(state: GameState): DrawPilePeekResult {
        val playableState = requireReadyForTurnAction(state, "peek at draw pile")
        val replenishedState = replenishDrawPileIfNeeded(playableState)
        return DrawPilePeekResult(
            state = replenishedState,
            card = replenishedState.drawPile.cards.last(),
        )
    }

    fun takeDiscardCard(state: GameState): GameState {
        val playableState = requireReadyForTurnAction(state, "take discard card")
        val drawResult = playableState.discardPile.takeTop()
        return playableState.copy(
            discardPile = drawResult.remainingPile,
            drawnCard = drawResult.card,
            drawSource = DrawSource.DISCARD,
            phase = GamePhase.AWAITING_REPLACEMENT,
            actionCardResult = null,
        )
    }

    fun drawVisibleActionCard(state: GameState, actionCardIndex: Int): GameState {
        val playableState = requireReadyForTurnAction(state, "draw an action card")
        if (actionCardIndex !in playableState.visibleActionCards.indices) {
            throw InvalidMoveException("visible action card index $actionCardIndex is not available")
        }

        var actionDrawPile = playableState.actionDrawPile
        val visibleActionCards = playableState.visibleActionCards.toMutableList()
        val actionCard = visibleActionCards[actionCardIndex]
        if (actionDrawPile.size > 0) {
            val replenishResult = actionDrawPile.draw()
            visibleActionCards[actionCardIndex] = replenishResult.card
            actionDrawPile = replenishResult.remainingPile
        } else {
            visibleActionCards.removeAt(actionCardIndex)
        }

        val currentPlayer = playableState.currentPlayer()
        val updatedPlayers = playableState.players.updated(
            playableState.currentPlayerIndex,
            currentPlayer.copy(actionCards = currentPlayer.actionCards + actionCard),
        )

        return advanceAfterTurn(
            playableState.copy(
                players = updatedPlayers,
                actionDrawPile = actionDrawPile,
                visibleActionCards = visibleActionCards,
                drawnCard = null,
                drawSource = null,
                actionCardResult = null,
            ),
        )
    }

    fun drawActionCardFromDeck(state: GameState): GameState {
        val playableState = requireReadyForTurnAction(state, "draw an action card")
        if (playableState.actionDrawPile.size == 0) {
            throw InvalidMoveException("cannot draw action card because the action draw pile is empty")
        }

        val drawResult = playableState.actionDrawPile.draw()
        val currentPlayer = playableState.currentPlayer()
        val updatedPlayers = playableState.players.updated(
            playableState.currentPlayerIndex,
            currentPlayer.copy(actionCards = currentPlayer.actionCards + drawResult.card),
        )

        return advanceAfterTurn(
            playableState.copy(
                players = updatedPlayers,
                actionDrawPile = drawResult.remainingPile,
                drawnCard = null,
                drawSource = null,
                actionCardResult = null,
            ),
        )
    }

    fun discardActionCard(state: GameState, actionCardIndex: Int): GameState =
        playOrDiscardActionCard(state, actionCardIndex, applyEffect = false)

    fun playActionCard(state: GameState, command: PlayActionCardCommand): GameState =
        when (val parameters = command.parameters) {
            is ActionCardParameters.DrawThreeCardsChoice ->
                completeDrawThreeCardsAction(state, command.actionCardIndex, parameters)
            else -> playOrDiscardActionCard(
                state = state,
                actionCardIndex = command.actionCardIndex,
                applyEffect = true,
                parameters = parameters,
            )
        }

    fun replaceDrawnCard(state: GameState, position: BoardPosition): GameState {
        val playableState = requireAwaitingReplacement(state)
        val currentPlayer = playableState.currentPlayer()
        val drawnCard = playableState.drawnCard ?: throw InvalidMoveException("no drawn card is available")
        val replacementResult = currentPlayer.board.replace(position, drawnCard)
        val cleanupResult = replacementResult.board.clearCompletedLines()

        val updatedDiscardPile = playableState.discardPile
            .add(replacementResult.replacedCard)
            .addAll(cleanupResult.removedCards)

        val updatedPlayers = playableState.players.updated(
            playableState.currentPlayerIndex,
            currentPlayer.copy(board = cleanupResult.board),
        )

        return advanceAfterTurn(
            playableState.copy(
                players = updatedPlayers,
                discardPile = updatedDiscardPile,
                drawnCard = null,
                drawSource = null,
                actionCardResult = null,
            ),
        )
    }

    fun discardDrawnCardAndReveal(state: GameState, position: BoardPosition): GameState {
        val playableState = requireAwaitingReplacement(state)
        if (playableState.drawSource != DrawSource.DECK) {
            throw InvalidMoveException("discard and reveal is only allowed after drawing from the deck")
        }

        val currentPlayer = playableState.currentPlayer()
        val slot = currentPlayer.board.slotAt(position)
        if (slot !is BoardSlot.Occupied || slot.faceUp) {
            throw InvalidMoveException("discard and reveal requires a face-down occupied slot")
        }

        val revealedBoard = currentPlayer.board.reveal(position)
        val cleanupResult = revealedBoard.clearCompletedLines()
        val drawnCard = playableState.drawnCard ?: throw InvalidMoveException("no drawn card is available")

        val updatedPlayers = playableState.players.updated(
            playableState.currentPlayerIndex,
            currentPlayer.copy(board = cleanupResult.board),
        )
        val updatedDiscardPile = playableState.discardPile
            .add(drawnCard)
            .addAll(cleanupResult.removedCards)

        return advanceAfterTurn(
            playableState.copy(
                players = updatedPlayers,
                discardPile = updatedDiscardPile,
                drawnCard = null,
                drawSource = null,
                actionCardResult = null,
            ),
        )
    }

    private fun validateSetup(
        playerIds: List<String>,
        initialReveals: Map<String, Set<BoardPosition>>,
    ) {
        if (playerIds.size !in 2..8) {
            throw InvalidGameSetupException("Skyjo requires between 2 and 8 players")
        }
        if (playerIds.distinct().size != playerIds.size) {
            throw InvalidGameSetupException("player ids must be unique")
        }
        if (playerIds.any { it.isBlank() }) {
            throw InvalidGameSetupException("player ids must not be blank")
        }
        if (initialReveals.keys != playerIds.toSet()) {
            throw InvalidGameSetupException("initial reveals must be provided for every player id")
        }
        if (initialReveals.values.any { it.size != 2 }) {
            throw InvalidGameSetupException("each player must reveal exactly two positions")
        }
    }

    private fun setupActionCards(actionDrawPile: ActionDrawPile): ActionCardSetup {
        var remainingPile = actionDrawPile
        val visibleCards = buildList {
            repeat(VISIBLE_ACTION_CARD_COUNT) {
                val drawResult = remainingPile.draw()
                add(drawResult.card)
                remainingPile = drawResult.remainingPile
            }
        }
        val discardResult = remainingPile.draw()
        return ActionCardSetup(
            drawPile = discardResult.remainingPile,
            visibleCards = visibleCards,
            discardPile = ActionDiscardPile(listOf(discardResult.card)),
        )
    }

    private fun requireActiveRound(state: GameState): GameState {
        when (state.phase) {
            GamePhase.NOT_STARTED -> throw GameNotStartedException("game has not been started yet")
            GamePhase.ROUND_FINISHED -> throw RoundAlreadyFinishedException("round has already finished")
            else -> return state
        }
    }

    private fun requireReadyForTurnAction(state: GameState, action: String): GameState {
        val playableState = requireActiveRound(state)
        if (playableState.phase != GamePhase.AWAITING_DRAW && playableState.phase != GamePhase.FINAL_TURNS) {
            throw InvalidMoveException("cannot $action while phase is ${playableState.phase}")
        }
        if (playableState.pendingActionCard != null) {
            throw InvalidMoveException("pending action card must be completed first")
        }
        return playableState
    }

    private fun requireAwaitingReplacement(state: GameState): GameState {
        val playableState = requireActiveRound(state)
        if (playableState.phase != GamePhase.AWAITING_REPLACEMENT) {
            throw InvalidMoveException("a card has to be drawn before this action")
        }
        if (playableState.pendingActionCard != null) {
            throw InvalidMoveException("pending action card must be completed first")
        }
        return playableState
    }

    private fun replenishDrawPileIfNeeded(state: GameState): GameState {
        if (state.drawPile.size > 0) {
            return state
        }

        if (state.discardPile.size <= 1) {
            throw InvalidMoveException("cannot replenish draw pile because the discard pile has no spare cards")
        }

        val protectedTopCard = state.discardPile.topCard()
        val cardsToShuffle = state.discardPile.cards.dropLast(1)
        val seed = state.shuffleSeed
        val random = if (seed != null) {
            Random(seed + state.shuffleCount.toLong() + 1L)
        } else {
            Random
        }
        val shuffledCards = cardsToShuffle.shuffled(random)

        return state.copy(
            drawPile = DrawPile(shuffledCards),
            discardPile = DiscardPile(listOf(protectedTopCard)),
            shuffleCount = state.shuffleCount + 1,
        )
    }

    private fun playOrDiscardActionCard(
        state: GameState,
        actionCardIndex: Int,
        applyEffect: Boolean,
        parameters: ActionCardParameters = ActionCardParameters.None,
    ): GameState {
        val playableState = requireReadyForTurnAction(state, "play or discard an action card")
        if (playableState.phase == GamePhase.FINAL_TURNS) {
            throw InvalidMoveException("action cards cannot be played or discarded during final turns")
        }

        val currentPlayer = playableState.currentPlayer()
        if (actionCardIndex !in currentPlayer.actionCards.indices) {
            throw InvalidMoveException("action card index $actionCardIndex is not available")
        }

        val actionCard = currentPlayer.actionCards[actionCardIndex]
        if (applyEffect && actionCard is SkyjoCard.ActionCard.Placeholder) {
            throw InvalidMoveException("placeholder action cards cannot be played")
        }
        if (applyEffect && actionCard is SkyjoCard.ActionCard.DrawThreeCards) {
            return startDrawThreeCardsAction(playableState, actionCardIndex, actionCard)
        }

        val remainingActionCards = currentPlayer.actionCards.filterIndexed { index, _ -> index != actionCardIndex }
        val updatedPlayers = playableState.players.updated(
            playableState.currentPlayerIndex,
            currentPlayer.copy(actionCards = remainingActionCards),
        )

        val stateAfterDiscard = playableState.copy(
            players = updatedPlayers,
            actionDiscardPile = playableState.actionDiscardPile.add(actionCard),
            drawnCard = null,
            drawSource = null,
            actionCardResult = null,
        )
        val stateAfterAction = if (applyEffect) {
            actionCard.toEffect().apply(stateAfterDiscard, parameters)
        } else {
            stateAfterDiscard
        }

        return advanceAfterTurn(stateAfterAction)
    }

    private fun startDrawThreeCardsAction(
        state: GameState,
        actionCardIndex: Int,
        actionCard: SkyjoCard.ActionCard.DrawThreeCards,
    ): GameState {
        val actingPlayerId = state.currentPlayerId
            ?: throw InvalidMoveException("current player is not available")
        var drawingState = state
        val drawnCards = buildList {
            repeat(DRAW_THREE_CARDS_COUNT) {
                drawingState = replenishDrawPileIfNeeded(drawingState)
                val drawResult = drawingState.drawPile.draw()
                add(drawResult.card)
                drawingState = drawingState.copy(drawPile = drawResult.remainingPile)
            }
        }

        return drawingState.copy(
            drawnCard = null,
            drawSource = null,
            actionCardResult = ActionCardResult.DrawThreeCards(
                actingPlayerId = actingPlayerId,
                cards = drawnCards,
            ),
            pendingActionCard = PendingActionCard.DrawThreeCards(
                actingPlayerId = actingPlayerId,
                actionCardIndex = actionCardIndex,
                actionCardId = actionCard.id,
                cards = drawnCards,
            ),
        )
    }

    private fun completeDrawThreeCardsAction(
        state: GameState,
        actionCardIndex: Int,
        parameters: ActionCardParameters.DrawThreeCardsChoice,
    ): GameState {
        val playableState = requireActiveRound(state)
        if (playableState.phase != GamePhase.AWAITING_DRAW) {
            throw InvalidMoveException("cannot complete Draw Three Cards while phase is ${playableState.phase}")
        }

        val pending = playableState.pendingActionCard as? PendingActionCard.DrawThreeCards
            ?: throw InvalidMoveException("no pending Draw Three Cards action")
        val currentPlayer = playableState.currentPlayer()
        if (pending.actingPlayerId != currentPlayer.id) {
            throw InvalidMoveException("pending Draw Three Cards action belongs to ${pending.actingPlayerId}")
        }
        if (parameters.targetPlayerId != null && parameters.targetPlayerId != currentPlayer.id) {
            throw InvalidMoveException("Draw Three Cards can only target your own board")
        }
        if (actionCardIndex != pending.actionCardIndex) {
            throw InvalidMoveException("action card index $actionCardIndex does not match the pending Draw Three Cards action")
        }
        val actionCard = currentPlayer.actionCards.getOrNull(actionCardIndex)
            ?: throw InvalidMoveException("action card index $actionCardIndex is not available")
        if (actionCard !is SkyjoCard.ActionCard.DrawThreeCards || actionCard.id != pending.actionCardId) {
            throw InvalidMoveException("pending Draw Three Cards action card is not available")
        }

        return when (parameters.mode) {
            DrawThreeCardsChoiceMode.KEEP_ONE_AND_SWAP -> completeDrawThreeCardsKeepOneAndSwap(
                playableState = playableState,
                currentPlayer = currentPlayer,
                pending = pending,
                actionCardIndex = actionCardIndex,
                actionCard = actionCard,
                parameters = parameters,
            )
            DrawThreeCardsChoiceMode.DISCARD_ALL_AND_REVEAL -> completeDrawThreeCardsDiscardAllAndReveal(
                playableState = playableState,
                currentPlayer = currentPlayer,
                pending = pending,
                actionCardIndex = actionCardIndex,
                actionCard = actionCard,
                parameters = parameters,
            )
        }
    }

    private fun completeDrawThreeCardsKeepOneAndSwap(
        playableState: GameState,
        currentPlayer: PlayerState,
        pending: PendingActionCard.DrawThreeCards,
        actionCardIndex: Int,
        actionCard: SkyjoCard.ActionCard,
        parameters: ActionCardParameters.DrawThreeCardsChoice,
    ): GameState {
        val chosenIndex = requiredDrawThreeCardsParameter(
            value = parameters.chosenDrawnCardIndex,
            fieldName = "chosenDrawnCardIndex",
            mode = parameters.mode,
        )
        if (chosenIndex !in pending.cards.indices) {
            throw InvalidMoveException("chosenDrawnCardIndex $chosenIndex is not available")
        }
        validateDrawThreeCardsSwapDiscardOrder(parameters.discardOrder, chosenIndex)

        val targetRow = requiredDrawThreeCardsParameter(parameters.targetRow, "targetRow", parameters.mode)
        val targetColumn = requiredDrawThreeCardsParameter(parameters.targetColumn, "targetColumn", parameters.mode)
        val targetPosition = boardPositionOrInvalid(targetRow, targetColumn)
        val targetSlot = currentPlayer.board.slotAt(targetPosition)
        if (targetSlot !is BoardSlot.Occupied) {
            throw InvalidMoveException("slot $targetPosition of current player is not occupied")
        }

        val replacementResult = currentPlayer.board.replace(targetPosition, pending.cards[chosenIndex])
        val cleanupResult = replacementResult.board.clearCompletedLines()
        val discardedCards = parameters.discardOrder.map { reference ->
            reference.toDrawThreeCardsDiscardedCard(pending.cards, replacementResult.replacedCard)
        }

        return completeDrawThreeCardsAction(
            playableState = playableState,
            currentPlayer = currentPlayer,
            actionCardIndex = actionCardIndex,
            actionCard = actionCard,
            updatedBoard = cleanupResult.board,
            discardedCards = discardedCards,
            cleanupCards = cleanupResult.removedCards,
        )
    }

    private fun completeDrawThreeCardsDiscardAllAndReveal(
        playableState: GameState,
        currentPlayer: PlayerState,
        pending: PendingActionCard.DrawThreeCards,
        actionCardIndex: Int,
        actionCard: SkyjoCard.ActionCard,
        parameters: ActionCardParameters.DrawThreeCardsChoice,
    ): GameState {
        validateDrawThreeCardsDiscardAllOrder(parameters.discardOrder)

        val revealRow = requiredDrawThreeCardsParameter(parameters.revealRow, "revealRow", parameters.mode)
        val revealColumn = requiredDrawThreeCardsParameter(parameters.revealColumn, "revealColumn", parameters.mode)
        val revealPosition = boardPositionOrInvalid(revealRow, revealColumn)
        val revealSlot = currentPlayer.board.slotAt(revealPosition)
        if (revealSlot !is BoardSlot.Occupied || revealSlot.faceUp) {
            throw InvalidMoveException("discard all and reveal requires a face-down occupied slot")
        }

        val revealedBoard = currentPlayer.board.reveal(revealPosition)
        val cleanupResult = revealedBoard.clearCompletedLines()
        val discardedCards = parameters.discardOrder.map { reference ->
            reference.toDrawThreeCardsDiscardedCard(pending.cards)
        }

        return completeDrawThreeCardsAction(
            playableState = playableState,
            currentPlayer = currentPlayer,
            actionCardIndex = actionCardIndex,
            actionCard = actionCard,
            updatedBoard = cleanupResult.board,
            discardedCards = discardedCards,
            cleanupCards = cleanupResult.removedCards,
        )
    }

    private fun completeDrawThreeCardsAction(
        playableState: GameState,
        currentPlayer: PlayerState,
        actionCardIndex: Int,
        actionCard: SkyjoCard.ActionCard,
        updatedBoard: PlayerBoard,
        discardedCards: List<SkyjoCard.PlayingCard>,
        cleanupCards: List<SkyjoCard.PlayingCard>,
    ): GameState {
        val remainingActionCards = currentPlayer.actionCards.filterIndexed { index, _ -> index != actionCardIndex }
        val updatedCurrentPlayer = currentPlayer.copy(
            board = updatedBoard,
            actionCards = remainingActionCards,
        )
        val updatedPlayers = playableState.players.updated(
            playableState.currentPlayerIndex,
            updatedCurrentPlayer,
        )

        return advanceAfterTurn(
            playableState.copy(
                players = updatedPlayers,
                discardPile = playableState.discardPile
                    .addAll(discardedCards)
                    .addAll(cleanupCards),
                actionDiscardPile = playableState.actionDiscardPile.add(actionCard),
                drawnCard = null,
                drawSource = null,
                actionCardResult = null,
                pendingActionCard = null,
            ),
        )
    }

    private fun requiredDrawThreeCardsParameter(
        value: Int?,
        fieldName: String,
        mode: DrawThreeCardsChoiceMode,
    ): Int = value ?: throw InvalidMoveException("$fieldName required for $mode")

    private fun boardPositionOrInvalid(row: Int, column: Int): BoardPosition =
        runCatching { BoardPosition(row, column) }
            .getOrElse { throw InvalidMoveException(it.message ?: "board position is not available") }

    private fun validateDrawThreeCardsSwapDiscardOrder(
        discardOrder: List<DrawThreeCardsDiscardReference>,
        chosenDrawnCardIndex: Int,
    ) {
        validateDrawThreeCardsDiscardOrderBasics(discardOrder)

        val expectedReferences = setOf(
            DrawThreeCardsDiscardReference.DRAWN_CARD_0,
            DrawThreeCardsDiscardReference.DRAWN_CARD_1,
            DrawThreeCardsDiscardReference.DRAWN_CARD_2,
            DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD,
        ) - drawnCardDiscardReference(chosenDrawnCardIndex)

        if (discardOrder.toSet() != expectedReferences) {
            throw InvalidMoveException("discardOrder must contain the two unchosen drawn cards and the swapped board card")
        }
    }

    private fun validateDrawThreeCardsDiscardAllOrder(discardOrder: List<DrawThreeCardsDiscardReference>) {
        validateDrawThreeCardsDiscardOrderBasics(discardOrder)

        val expectedReferences = setOf(
            DrawThreeCardsDiscardReference.DRAWN_CARD_0,
            DrawThreeCardsDiscardReference.DRAWN_CARD_1,
            DrawThreeCardsDiscardReference.DRAWN_CARD_2,
        )

        if (discardOrder.toSet() != expectedReferences) {
            throw InvalidMoveException("discardOrder must contain all three drawn cards")
        }
    }

    private fun validateDrawThreeCardsDiscardOrderBasics(discardOrder: List<DrawThreeCardsDiscardReference>) {
        if (discardOrder.size != DRAW_THREE_CARDS_COUNT) {
            throw InvalidMoveException("discardOrder must contain exactly three cards")
        }
        if (discardOrder.toSet().size != discardOrder.size) {
            throw InvalidMoveException("discardOrder must not contain duplicates")
        }
    }

    private fun drawnCardDiscardReference(index: Int): DrawThreeCardsDiscardReference =
        when (index) {
            0 -> DrawThreeCardsDiscardReference.DRAWN_CARD_0
            1 -> DrawThreeCardsDiscardReference.DRAWN_CARD_1
            2 -> DrawThreeCardsDiscardReference.DRAWN_CARD_2
            else -> throw InvalidMoveException("chosenDrawnCardIndex $index is not available")
        }

    private fun DrawThreeCardsDiscardReference.toDrawThreeCardsDiscardedCard(
        drawnCards: List<SkyjoCard.PlayingCard>,
        swappedBoardCard: SkyjoCard.PlayingCard,
    ): SkyjoCard.PlayingCard =
        when (this) {
            DrawThreeCardsDiscardReference.DRAWN_CARD_0 -> drawnCards[0]
            DrawThreeCardsDiscardReference.DRAWN_CARD_1 -> drawnCards[1]
            DrawThreeCardsDiscardReference.DRAWN_CARD_2 -> drawnCards[2]
            DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD -> swappedBoardCard
        }

    private fun DrawThreeCardsDiscardReference.toDrawThreeCardsDiscardedCard(
        drawnCards: List<SkyjoCard.PlayingCard>,
    ): SkyjoCard.PlayingCard =
        when (this) {
            DrawThreeCardsDiscardReference.DRAWN_CARD_0 -> drawnCards[0]
            DrawThreeCardsDiscardReference.DRAWN_CARD_1 -> drawnCards[1]
            DrawThreeCardsDiscardReference.DRAWN_CARD_2 -> drawnCards[2]
            DrawThreeCardsDiscardReference.SWAPPED_BOARD_CARD ->
                throw InvalidMoveException("discardOrder must contain all three drawn cards")
        }

    private fun advanceAfterTurn(state: GameState): GameState {
        val currentPlayer = state.currentPlayer()
        val finisherTriggered = !currentPlayer.board.hasHiddenCards()

        if (state.finisherPlayerId == null && finisherTriggered) {
            val finalTurns = state.players.size - 1
            val nextPlayerIndex = nextPlayerIndex(state.currentPlayerIndex, state.players.size)
            if (finalTurns <= 0) {
                return finishRoundAfterTurn(state.copy(finisherPlayerId = currentPlayer.id, finalTurnsRemaining = 0))
            }

            return state.copy(
                currentPlayerIndex = nextPlayerIndex,
                phase = GamePhase.FINAL_TURNS,
                finisherPlayerId = currentPlayer.id,
                finalTurnsRemaining = finalTurns,
            )
        }

        if (state.pendingExtraTurns > 0) {
            return state.copy(
                phase = if (state.finisherPlayerId == null) GamePhase.AWAITING_DRAW else GamePhase.FINAL_TURNS,
                pendingExtraTurns = state.pendingExtraTurns - 1,
            )
        }

        if (state.finisherPlayerId != null) {
            val remainingFinalTurns = state.finalTurnsRemaining - 1
            if (remainingFinalTurns <= 0) {
                return finishRoundAfterTurn(state.copy(finalTurnsRemaining = 0))
            }

            return state.copy(
                currentPlayerIndex = nextPlayerIndex(state.currentPlayerIndex, state.players.size),
                phase = GamePhase.FINAL_TURNS,
                finalTurnsRemaining = remainingFinalTurns,
            )
        }

        return state.copy(
            currentPlayerIndex = nextPlayerIndex(state.currentPlayerIndex, state.players.size),
            phase = GamePhase.AWAITING_DRAW,
        )
    }

    private fun finishRoundAfterTurn(state: GameState): GameState {
        val actionCardResult = state.actionCardResult
        return finishRound(state).copy(actionCardResult = actionCardResult)
    }

    internal fun finishRound(state: GameState): GameState {
        val finisherPlayerId = state.finisherPlayerId ?: throw InvalidMoveException("cannot finish round without a finisher")
        var updatedDiscardPile = state.discardPile
        val revealedPlayers = state.players.map { player ->
            val revealedBoard = player.board.fullyReveal()
            val cleanupResult = revealedBoard.clearCompletedLines()
            updatedDiscardPile = updatedDiscardPile.addAll(cleanupResult.removedCards)
            player.copy(board = cleanupResult.board)
        }

        val rawScores = revealedPlayers.associate { player -> player.id to player.rawScore() }
        val finisherScore = rawScores.getValue(finisherPlayerId)
        val mustDoubleFinisher = finisherScore > 0 && rawScores.any { (playerId, score) ->
            playerId != finisherPlayerId && score <= finisherScore
        }

        val roundResult = RoundResult(
            finisherPlayerId = finisherPlayerId,
            scores = revealedPlayers.map { player ->
                val rawScore = rawScores.getValue(player.id)
                RoundResult.PlayerRoundScore(
                    playerId = player.id,
                    rawScore = rawScore,
                    finalScore = when {
                        player.id == finisherPlayerId && mustDoubleFinisher -> rawScore * 2
                        else -> rawScore
                    },
                )
            },
        )

        return state.copy(
            players = revealedPlayers,
            discardPile = updatedDiscardPile,
            phase = GamePhase.ROUND_FINISHED,
            drawnCard = null,
            drawSource = null,
            finalTurnsRemaining = 0,
            roundResult = roundResult,
            actionCardResult = null,
            pendingActionCard = null,
        )
    }

    private fun nextPlayerIndex(currentIndex: Int, playerCount: Int): Int = (currentIndex + 1) % playerCount

    internal fun determineStartingPlayerIndex(
        players: List<PlayerState>,
        initialReveals: Map<String, Set<BoardPosition>>,
    ): Int =
        players.indices.maxByOrNull { index ->
            players[index].board.visibleValueSum(initialReveals.getValue(players[index].id))
        } ?: 0
}

private fun <T> List<T>.updated(index: Int, value: T): List<T> = mapIndexed { currentIndex, item ->
    if (currentIndex == index) value else item
}

private data class ActionCardSetup(
    val drawPile: ActionDrawPile,
    val visibleCards: List<SkyjoCard.ActionCard>,
    val discardPile: ActionDiscardPile,
)

data class DrawPilePeekResult(
    val state: GameState,
    val card: SkyjoCard.PlayingCard,
)
