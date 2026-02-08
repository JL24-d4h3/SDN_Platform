package org.sdn_controller_app.repository

import org.sdn_controller_app.model.RequestSession
import org.sdn_controller_app.model.SessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SessionRepository : JpaRepository<RequestSession, String> {

    /** Sesiones activas (no cerradas) de un dispositivo. */
    fun findByOriginMacAndStatusNot(originMac: String, status: SessionStatus): List<RequestSession>

    /** Sesiones activas de un dispositivo en un estado específico. */
    fun findByOriginMacAndStatus(originMac: String, status: SessionStatus): List<RequestSession>

    /** Todas las sesiones de un dispositivo, ordenadas por creación descendente. */
    fun findByOriginMacOrderByCreatedAtDesc(originMac: String): List<RequestSession>

    /** Sesiones que aún no se han cerrado. */
    fun findByStatusNot(status: SessionStatus): List<RequestSession>
}
