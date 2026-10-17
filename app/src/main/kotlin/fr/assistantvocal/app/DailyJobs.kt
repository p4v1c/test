package fr.assistantvocal.app

import java.time.LocalTime
import java.time.ZonedDateTime

/** Tâches quotidiennes programmées : « tout va bien ? ». */
object DailyJobs {
    const val WELLBEING_ID = 900_001
    const val WELLBEING_FOLLOW_UP_ID = 900_002
    const val K_ATTEMPTS = "wellbeing.attempts"

    /** Programme (ou annule) la prochaine question « tout va bien ? » selon les réglages. */
    fun scheduleWellbeing(graph: AppGraph) {
        val time = runCatching { LocalTime.parse(graph.settings.wellbeingTime) }.getOrNull()
        if (time == null || graph.people.emergencyContacts.isEmpty()) { graph.phone.scheduler.cancel(WELLBEING_ID); return }
        val now = ZonedDateTime.now()
        var next = now.with(time).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        graph.phone.scheduler.schedule(WELLBEING_ID, next.toInstant().toEpochMilli())
    }
}
