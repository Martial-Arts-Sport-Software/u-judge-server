package org.mass.domain

enum class SessionState {
    PREPARED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED,
}

class SessionProjection private constructor(
    val sessionId: SessionId,
    val state: SessionState,
) {
    fun transitionTo(nextState: SessionState): SessionProjection {
        check(nextState in transitions.getValue(state)) {
            "Cannot transition a $state session to $nextState"
        }
        return SessionProjection(sessionId, nextState)
    }

    companion object {
        fun initial(sessionId: SessionId): SessionProjection = SessionProjection(sessionId, SessionState.PREPARED)

        fun rebuild(sessionId: SessionId, states: Iterable<SessionState>): SessionProjection =
            states.fold(initial(sessionId)) { projection, state -> projection.transitionTo(state) }

        private val transitions = mapOf(
            SessionState.PREPARED to setOf(SessionState.RUNNING, SessionState.CANCELLED),
            SessionState.RUNNING to setOf(SessionState.PAUSED, SessionState.COMPLETED, SessionState.CANCELLED),
            SessionState.PAUSED to setOf(SessionState.RUNNING, SessionState.CANCELLED),
            SessionState.COMPLETED to emptySet(),
            SessionState.CANCELLED to emptySet(),
        )
    }
}
