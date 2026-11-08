package org.mass.report

import org.mass.RealtimeHub
import org.mass.applications.CompetitionApplicationsService
import org.mass.draw.CompetitionCourtsService
import org.mass.protocol.ProtocolDocument
import org.mass.protocol.ProtocolKind
import java.time.Instant
import java.time.ZoneId

/**
 * Reports of the current competition for the desktop (ADR-012): the history, XLSX, CSV and protocols, rebuilt from the projections of
 * the running server on every call; null before an import.
 */
class CompetitionReports(
    private val applications: CompetitionApplicationsService,
    private val courts: CompetitionCourtsService,
    private val hub: RealtimeHub,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun report(generatedAt: Instant = Instant.now()): CompetitionReport? {
        val current = applications.history.value.current?.applications ?: return null
        val board = courts.board().takeIf { it.competitionId != null } ?: return null
        return CompetitionReport.of(current, board, hub.bouts.bouts(), hub.performances.performances(), generatedAt, zone())
    }

    fun xlsx(generatedAt: Instant = Instant.now()): ByteArray? = report(generatedAt)?.let(XlsxReport::render)

    fun csv(generatedAt: Instant = Instant.now()): ByteArray? = report(generatedAt)?.let(CsvReport::render)

    /**
     * «Протокол хода соревнований» with winners and scores, or «Протокол результатов» with the places, of [categoryIds]
     * (all confirmed categories when null), with the seal and signatures (ADR-012 §5).
     */
    fun protocol(kind: ProtocolKind, categoryIds: Collection<String>? = null): ProtocolDocument? {
        val current = applications.history.value.current?.applications ?: return null
        val report = report() ?: return null
        return ProtocolDocument.of(courts.board(), current, categoryIds, report, kind)
    }
}
