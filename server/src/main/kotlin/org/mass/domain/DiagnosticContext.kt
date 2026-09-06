package org.mass.domain

data class DiagnosticContext(
    val eventId: EventId,
    val competitionId: CompetitionId,
    val peerId: PeerId,
    val courtId: CourtId,
    val bracketId: BracketId,
    val sessionId: SessionId,
    val judgeId: JudgeId,
    val deviceId: DeviceId,
)

fun DomainEvent.diagnosticContext() = DiagnosticContext(
    eventId, competitionId, peerId, courtId, bracketId, sessionId, judgeId, deviceId,
)
