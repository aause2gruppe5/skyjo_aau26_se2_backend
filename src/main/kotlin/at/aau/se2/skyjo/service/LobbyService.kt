package at.aau.se2.skyjo.service

import at.aau.se2.skyjo.model.auth.AuthUserDto
import at.aau.se2.skyjo.model.lobby.LobbyPlayer
import at.aau.se2.skyjo.model.lobby.LobbyState
import at.aau.se2.skyjo.model.lobby.LobbyStatus
import at.aau.se2.skyjo.persistence.LobbyMemberRecord
import at.aau.se2.skyjo.persistence.LobbyRecord
import at.aau.se2.skyjo.persistence.LobbyRepository
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface JoinCodeGenerator {
    fun generateCode(): String
}

class RandomJoinCodeGenerator : JoinCodeGenerator {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    override fun generateCode(): String =
        (1..6)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString(separator = "")
}

interface LobbyIdGenerator {
    fun generateId(): String
}

class RandomLobbyIdGenerator : LobbyIdGenerator {
    override fun generateId(): String = UUID.randomUUID().toString()
}

class LobbyService(
    private val repository: LobbyRepository? = null,
    private val joinCodeGenerator: JoinCodeGenerator = RandomJoinCodeGenerator(),
    private val idGenerator: LobbyIdGenerator = RandomLobbyIdGenerator(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = ReentrantLock()
    private var state = LobbyState()
    private val inMemoryLobbies = mutableMapOf<String, LobbyState>()
    private val inMemoryUserLobbyIds = mutableMapOf<String, String>()

    fun join(sessionId: String, nickname: String): LobbyState = lock.withLock {
        if (state.status == LobbyStatus.IN_GAME) {
            error("cannot join: game already in progress")
        }
        if (state.players.size >= state.maxPlayers) {
            error("cannot join: lobby is full (max ${state.maxPlayers} players)")
        }
        if (state.players.any { it.sessionId == sessionId }) {
            return state
        }

        val isHost = state.players.isEmpty()
        val player = LobbyPlayer(sessionId = sessionId, nickname = nickname, isHost = isHost)
        state = state.copy(players = state.players + player)
        state
    }

    fun leave(sessionId: String): LobbyState = lock.withLock {
        val players = state.players.filter { it.sessionId != sessionId }
        val reassigned = if (players.isNotEmpty() && players.none { it.isHost }) {
            players.mapIndexed { i, p -> if (i == 0) p.copy(isHost = true) else p }
        } else {
            players
        }
        val newStatus = if (reassigned.isEmpty()) LobbyStatus.WAITING else state.status
        state = state.copy(players = reassigned, status = newStatus)
        state
    }

    fun startGame(sessionId: String): LobbyState = lock.withLock {
        val caller = state.players.find { it.sessionId == sessionId }
            ?: error("player not in lobby")
        if (!caller.isHost) {
            error("only the host can start the game")
        }
        if (state.players.size < 2) {
            error("need at least 2 players to start")
        }
        state = state.copy(status = LobbyStatus.IN_GAME)
        state
    }

    fun reset(): LobbyState = lock.withLock {
        state = LobbyState()
        state
    }

    fun getState(): LobbyState = lock.withLock { state }

    fun isPlayerInLobby(sessionId: String): Boolean = lock.withLock {
        state.players.any { it.sessionId == sessionId }
    }

    fun createLobby(user: AuthUserDto): LobbyState = lock.withLock {
        ensureUserCanEnterLobby(user.userId)
        val lobbyId = idGenerator.generateId()
        val joinCode = generateUniqueJoinCode()
        val now = nowProvider()
        val host = LobbyPlayer(
            sessionId = user.userId,
            nickname = user.username,
            isHost = true,
            userId = user.userId,
        )

        repository?.let { repo ->
            repo.createLobby(lobbyId, joinCode, user.userId, maxPlayers = DEFAULT_MAX_PLAYERS, now = now)
            repo.upsertMember(lobbyId, user.userId, user.username, isHost = true, joinedAt = now)
        } ?: run {
            inMemoryLobbies[lobbyId] = LobbyState(
                lobbyId = lobbyId,
                joinCode = joinCode,
                players = listOf(host),
                maxPlayers = DEFAULT_MAX_PLAYERS,
            )
            inMemoryUserLobbyIds[user.userId] = lobbyId
        }

        getLobbyById(lobbyId) ?: error("created lobby is not available")
    }

    fun joinLobby(user: AuthUserDto, joinCode: String): LobbyState = lock.withLock {
        val lobby = getLobbyByJoinCode(joinCode) ?: error("lobby not found")
        if (lobby.status != LobbyStatus.WAITING) {
            error("cannot join: lobby is not waiting")
        }
        val existingLobby = getCurrentLobbyForUser(user.userId)
        if (existingLobby != null && existingLobby.lobbyId != lobby.lobbyId) {
            error("user is already in a lobby")
        }
        if (lobby.players.any { it.userId == user.userId }) {
            return lobby
        }
        if (lobby.players.size >= lobby.maxPlayers) {
            error("cannot join: lobby is full (max ${lobby.maxPlayers} players)")
        }

        val lobbyId = lobby.lobbyId ?: error("lobby id is missing")
        val now = nowProvider()
        repository?.upsertMember(lobbyId, user.userId, user.username, isHost = false, joinedAt = now)
            ?: run {
                inMemoryLobbies[lobbyId] = lobby.copy(
                    players = lobby.players + LobbyPlayer(
                        sessionId = user.userId,
                        nickname = user.username,
                        isHost = false,
                        userId = user.userId,
                    ),
                )
                inMemoryUserLobbyIds[user.userId] = lobbyId
            }

        getLobbyById(lobbyId) ?: error("joined lobby is not available")
    }

    fun leaveLobby(userId: String, lobbyId: String): LobbyState = lock.withLock {
        val lobby = getLobbyById(lobbyId) ?: error("lobby not found")
        val remainingPlayers = lobby.players.filter { it.userId != userId }

        repository?.removeMember(lobbyId, userId) ?: inMemoryUserLobbyIds.remove(userId)

        if (remainingPlayers.isEmpty()) {
            repository?.updateLobbyStatus(lobbyId, LobbyStatus.CLOSED, nowProvider())
                ?: run { inMemoryLobbies[lobbyId] = lobby.copy(players = emptyList(), status = LobbyStatus.CLOSED) }
            return getLobbyById(lobbyId) ?: lobby.copy(players = emptyList(), status = LobbyStatus.CLOSED)
        }

        if (remainingPlayers.none { it.isHost }) {
            val newHost = remainingPlayers.first()
            repository?.setHost(lobbyId, newHost.userId, isHost = true)
                ?: run {
                    inMemoryLobbies[lobbyId] = lobby.copy(
                        players = remainingPlayers.mapIndexed { index, player ->
                            if (index == 0) player.copy(isHost = true) else player.copy(isHost = false)
                        },
                    )
                }
        }

        getLobbyById(lobbyId) ?: error("updated lobby is not available")
    }

    fun startGame(userId: String, lobbyId: String): LobbyState = lock.withLock {
        val lobby = getLobbyById(lobbyId) ?: error("lobby not found")
        val caller = lobby.players.find { it.userId == userId } ?: error("player not in lobby")
        if (!caller.isHost) {
            error("only the host can start the game")
        }
        if (lobby.players.size < 2) {
            error("need at least 2 players to start")
        }

        repository?.updateLobbyStatus(lobbyId, LobbyStatus.IN_GAME, nowProvider())
            ?: run { inMemoryLobbies[lobbyId] = lobby.copy(status = LobbyStatus.IN_GAME) }

        getLobbyById(lobbyId) ?: error("started lobby is not available")
    }

    fun getLobbyById(lobbyId: String): LobbyState? = lock.withLock {
        repository?.let { repo ->
            repo.findLobbyById(lobbyId)?.let { record ->
                record.toState(repo.listMembers(record.lobbyId))
            }
        } ?: inMemoryLobbies[lobbyId]
    }

    fun getCurrentLobbyForUser(userId: String): LobbyState? = lock.withLock {
        repository?.let { repo ->
            repo.findCurrentLobbyForUser(userId)?.let { record ->
                record.toState(repo.listMembers(record.lobbyId))
            }
        } ?: inMemoryUserLobbyIds[userId]?.let(inMemoryLobbies::get)
    }

    private fun getLobbyByJoinCode(joinCode: String): LobbyState? {
        return repository?.let { repo ->
            repo.findLobbyByJoinCode(joinCode)?.let { record ->
                record.toState(repo.listMembers(record.lobbyId))
            }
        } ?: inMemoryLobbies.values.firstOrNull { it.joinCode.equals(joinCode, ignoreCase = true) }
    }

    private fun ensureUserCanEnterLobby(userId: String) {
        val current = getCurrentLobbyForUser(userId)
        if (current != null && current.status == LobbyStatus.WAITING) {
            error("user is already in a lobby")
        }
    }

    private fun generateUniqueJoinCode(): String {
        repeat(10) {
            val code = joinCodeGenerator.generateCode()
            if (getLobbyByJoinCode(code) == null) {
                return code
            }
        }
        error("could not generate unique lobby code")
    }

    private fun LobbyRecord.toState(members: List<LobbyMemberRecord>) = LobbyState(
        lobbyId = lobbyId,
        joinCode = joinCode,
        players = members.map { member ->
            LobbyPlayer(
                sessionId = member.userId,
                nickname = member.username,
                isHost = member.isHost,
                userId = member.userId,
            )
        },
        status = status,
        maxPlayers = maxPlayers,
    )

    private companion object {
        const val DEFAULT_MAX_PLAYERS = 6
    }
}
