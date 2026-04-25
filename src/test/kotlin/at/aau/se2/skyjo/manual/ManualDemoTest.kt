package at.aau.se2.skyjo.manual

import at.aau.se2.skyjo.game.model.BoardLayout
import at.aau.se2.skyjo.game.model.BoardPosition
import at.aau.se2.skyjo.game.model.BoardSlot
import at.aau.se2.skyjo.game.model.GameState
import at.aau.se2.skyjo.game.model.scoreValue
import at.aau.se2.skyjo.game.service.SkyjoEngine

fun main() {
    val engine = SkyjoEngine()

    var state = engine.startGame(
        playerIds = listOf("Alice", "Bob"),
        initialReveals = mapOf(
            "Alice" to setOf(BoardPosition(0, 0), BoardPosition(1, 1)),
            "Bob" to setOf(BoardPosition(0, 1), BoardPosition(2, 3)),
        ),
        seed = 42L,
    )

    printState("Nach Spielstart", state)

    state = engine.drawFromDeck(state)
    printState("Nach drawFromDeck()", state)

    val revealPosition = state.currentPlayer().board.hiddenPositions().first()
    state = engine.discardDrawnCardAndReveal(state, revealPosition)
    printState("Nach discardDrawnCardAndReveal($revealPosition)", state)

    state = engine.takeDiscardCard(state)
    printState("Nach takeDiscardCard()", state)

    val replacePosition = firstOccupiedPosition(state)
    state = engine.replaceDrawnCard(state, replacePosition)
    printState("Nach replaceDrawnCard($replacePosition)", state)
}

private fun printState(label: String, state: GameState) {
    println("=".repeat(70))
    println(label)
    println("Phase: ${state.phase}")
    println("Aktiver Spieler: ${state.currentPlayerId}")
    println("Gezogene Karte: ${state.drawnCard?.scoreValue() ?: "-"}")
    println("Ziehquelle: ${state.drawSource ?: "-"}")
    println("Draw pile size: ${state.drawPile.size}")
    println("Discard pile top card: ${state.discardPile.topCard().scoreValue()}")
    println("Final turns remaining: ${state.finalTurnsRemaining}")
    println()

    state.players.forEach { player ->
        println("Spieler ${player.id}")
        for (row in 0 until BoardLayout.ROWS) {
            val line = (0 until BoardLayout.COLUMNS).joinToString(" | ") { column ->
                val slot = player.board.slotAt(BoardPosition(row, column))
                when (slot) {
                    is BoardSlot.Cleared -> "XX"
                    is BoardSlot.Occupied -> {
                        val prefix = if (slot.faceUp) "O" else "X"
                        "$prefix${slot.card.scoreValue()}"
                    }
                }
            }
            println(line)
        }
        println("Raw score: ${player.board.rawScore()}")
        println()
    }

    state.roundResult?.let { result ->
        println("Rundenergebnis, Finisher: ${result.finisherPlayerId}")
        result.scores.forEach { score ->
            println("${score.playerId}: raw=${score.rawScore}, final=${score.finalScore}")
        }
        println()
    }
}

private fun firstOccupiedPosition(state: GameState): BoardPosition =
    BoardLayout.POSITIONS.first { position ->
        state.currentPlayer().board.slotAt(position) is BoardSlot.Occupied
    }
