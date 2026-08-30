package dev.morphhid.app.data

import android.content.Context
import dev.morphhid.core.control.ProfileValidator
import dev.morphhid.core.hid.CompiledHid
import dev.morphhid.core.hid.HidDescriptorCompiler
import dev.morphhid.core.profile.Profile
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed profile store. Profiles live as JSON documents under
 * filesDir/profiles; bundled samples are copied there on first run.
 */
class ProfileRepository(private val context: Context) {

    data class StoredProfile(
        val fileName: String,
        val profile: Profile,
        val compiled: CompiledHid,
        val issues: List<ProfileValidator.Issue>,
    ) {
        val hasErrors: Boolean get() = issues.any { it.severity == ProfileValidator.Severity.ERROR }
    }

    data class ParseOutcome(
        val stored: StoredProfile?,
        val error: String? = null,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val dir: File get() = File(context.filesDir, "profiles").apply { mkdirs() }

    fun list(): List<StoredProfile> =
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".json") || f.name.endsWith(".mhid")) }
            ?.mapNotNull { f -> parse(f).getOrNull()?.let { StoredProfile(f.name, it, compileOrThrow(it), validate(it)) } }
            .orEmpty()
            .sortedBy { it.profile.device.name.lowercase() }

    fun save(fileName: String, content: String): ParseOutcome {
        val parsed = parse(fileName, content)
        if (parsed == null) {
            return ParseOutcome(null, "Invalid JSON or unknown schema fields")
        }
        val issues = validate(parsed)
        if (issues.any { it.severity == ProfileValidator.Severity.ERROR }) {
            return ParseOutcome(null, issues.filter { it.severity == ProfileValidator.Severity.ERROR }
                .joinToString("; ") { it.message })
        }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "profile-${System.currentTimeMillis()}.json" }
            .let { if (it.endsWith(".json") || it.endsWith(".mhid")) it else "$it.json" }
        File(dir, safeName).writeText(content)
        return ParseOutcome(StoredProfile(safeName, parsed, compileOrThrow(parsed), issues))
    }

    fun delete(fileName: String) {
        File(dir, fileName).delete()
    }

    fun readText(fileName: String): String? = File(dir, fileName).takeIf { it.exists() }?.readText()

    /** Copies bundled sample profiles into the store once. */
    fun ensureSamples() {
        try {
            context.assets.list("samples")?.forEach { name ->
                val target = File(dir, name)
                if (!target.exists()) {
                    context.assets.open("samples/$name").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ProfileRepository", "sample copy failed", e)
        }
    }

    private fun parse(f: File): Result<Profile> = parse(f.name, f.readText()).let {
        if (it == null) Result.failure(IllegalArgumentException("parse failed")) else Result.success(it)
    }

    private fun parse(fileName: String, content: String): Profile? = try {
        json.decodeFromString<Profile>(content)
    } catch (e: Exception) {
        android.util.Log.w("ProfileRepository", "parse failed for $fileName: ${e.message}")
        null
    }

    private fun validate(profile: Profile): List<ProfileValidator.Issue> =
        try {
            ProfileValidator().validate(profile)
        } catch (e: Exception) {
            listOf(ProfileValidator.Issue(ProfileValidator.Severity.ERROR, e.message ?: "validation failed"))
        }

    private fun compileOrThrow(profile: Profile): CompiledHid =
        HidDescriptorCompiler().compile(profile)
}