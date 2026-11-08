package org.mass.report

import org.mass.RealtimeHub
import org.mass.applications.CompetitionApplicationsService
import org.mass.draw.CompetitionCourtsService
import java.time.Instant
import java.time.ZoneId

/**
 * Reports of the current competition for the desktop (ADR-012): the history, XLSX and CSV, rebuilt from the projections of
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
}
