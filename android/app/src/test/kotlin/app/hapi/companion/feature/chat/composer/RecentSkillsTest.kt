package app.hapi.companion.feature.chat.composer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.hapi.protocol.wire.SkillSummary
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class RecentSkillsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `normalized history survives reopening and is isolated by hub and namespace`() = runTest {
        val file = File(tmp.root, "skills.preferences_pb")
        fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(scope = scope) { file }
        val first = SupervisorJob()
        try {
            val history = DataStoreRecentSkills(store(CoroutineScope(Dispatchers.IO + first)), "https://a.test", flowOf("owner"))
            history.record("Zebra")
            history.record("Code-Review")
            history.record("ZEBRA")
            assertEquals(listOf("zebra", "code-review"), history.names.first())
        } finally {
            first.cancelAndJoin()
        }
        val second = SupervisorJob()
        try {
            val data = store(CoroutineScope(Dispatchers.IO + second))
            assertEquals(listOf("zebra", "code-review"), DataStoreRecentSkills(data, "https://a.test", flowOf("owner")).names.first())
            assertEquals(emptyList(), DataStoreRecentSkills(data, "https://b.test", flowOf("owner")).names.first())
            assertEquals(emptyList(), DataStoreRecentSkills(data, "https://a.test", flowOf("guest")).names.first())
        } finally {
            second.cancelAndJoin()
        }
    }

    @Test fun `unknown session namespace leaves history disabled until the chat has an identity`() = runTest {
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
                File(tmp.root, "session-identity.preferences_pb")
            }
            val hub = "https://hub.test"
            val owner = DataStoreRecentSkills(data, hub, flowOf("owner"))
            val guest = DataStoreRecentSkills(data, hub, flowOf("guest"))
            owner.record("Owner Skill")
            guest.record("Guest Skill")
            val namespace = MutableStateFlow<String?>(null)
            val history = DataStoreRecentSkills(data, hub, namespace)

            assertEquals(emptyList(), history.names.first())
            history.record("Unknown Skill")
            namespace.value = "owner"
            assertEquals(listOf("owner skill"), history.names.first())
            history.record("New Owner Skill")
            namespace.value = "guest"
            assertEquals(listOf("guest skill"), history.names.first())
            namespace.value = null
            assertEquals(emptyList(), history.names.first())
            assertEquals(listOf("new owner skill", "owner skill"), owner.names.first())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun `history remembers only the last 200 distinct skills`() = runTest {
        val job = SupervisorJob()
        try {
            val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
                File(tmp.root, "bounded.preferences_pb")
            }
            val history = DataStoreRecentSkills(data, "https://hub.test", flowOf("default"))
            repeat(202) { history.record("Skill-$it") }
            history.record("   ")
            assertEquals((201 downTo 2).map { "skill-$it" }, history.names.first())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun `unreadable preferences leave recency empty`() = runTest {
        val data = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("unavailable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("unavailable")
        }
        assertEquals(emptyList(), DataStoreRecentSkills(data, "https://hub.test", flowOf("default")).names.first())
    }

    @Test fun `recency only breaks ties and never adds skills missing from the catalogue`() {
        val skills = listOf("pre-Review", "Review-old", "Review", "Review-New", "Other").map { SkillSummary(it) }
        val recent = listOf("missing", "pre-review", "review-old")
        assertEquals(listOf("pre-Review", "Review-old", "Other", "Review", "Review-New"),
            SkillSuggestions.filter(skills, "", recent).map { it.name })
        assertEquals(listOf("Review", "Review-old", "Review-New", "pre-Review"),
            SkillSuggestions.filter(skills, "rEvIeW", recent).map { it.name })
    }
}
