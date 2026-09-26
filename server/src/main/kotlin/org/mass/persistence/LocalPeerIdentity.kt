package org.mass.persistence

import org.mass.domain.PeerId
import org.mass.replication.JournalSchema
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Stable identity of this desktop peer, created once per cluster and reused after every restart. */
object LocalPeerIdentity {
    fun loadOrCreate(dataSource: DataSource, now: () -> Instant = Instant::now): PeerId {
        JournalSchema.migrate(dataSource)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT peer_id FROM local_peer").use { statement ->
                statement.executeQuery().use { result -> if (result.next()) return PeerId(result.getString(1)) }
            }
            val peerId = PeerId(UUID.randomUUID().toString())
            connection.prepareStatement("INSERT INTO local_peer (peer_id, created_at) VALUES (?, ?)").use { statement ->
                statement.setString(1, peerId.value)
                statement.setString(2, now().toString())
                statement.executeUpdate()
            }
            return peerId
        }
    }
}
