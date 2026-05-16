package at.aau.se2.skyjo.game.model

data class PlayActionCardOutcome(
    val gameState: GameState,
    val privateActionCardResults: Map<String, ActionCardResult> = emptyMap(),
)
