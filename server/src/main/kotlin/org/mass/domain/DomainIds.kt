package org.mass.domain

import java.util.UUID

@JvmInline
value class CompetitionId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = CompetitionId(newUuid()) }
}

@JvmInline
value class PeerId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = PeerId(newUuid()) }
}

@JvmInline
value class CourtId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = CourtId(newUuid()) }
}

@JvmInline
value class BracketId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = BracketId(newUuid()) }
}

@JvmInline
value class SessionId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = SessionId(newUuid()) }
}

@JvmInline
value class JudgeId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = JudgeId(newUuid()) }
}

@JvmInline
value class DeviceId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = DeviceId(newUuid()) }
}

@JvmInline
value class EventId(val value: String) {
    init { requireCanonicalUuid(value) }
    companion object { fun new() = EventId(newUuid()) }
}

private fun newUuid(): String = UUID.randomUUID().toString()

private fun requireCanonicalUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
        "Domain identifier must be a canonical UUID"
    }
}
