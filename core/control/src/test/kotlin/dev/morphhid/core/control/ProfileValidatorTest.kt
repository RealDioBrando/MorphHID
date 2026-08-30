package dev.morphhid.core.control

import dev.morphhid.core.profile.Profile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val deckMini = """
    {
      "schemaVersion": 1,
      "device": {
        "name": "Deck Mini",
        "description": "Macro pad",
        "hid": {
          "subclass": "combo",
          "collections": [
            { "type": "keyboard", "reportId": 1 },
            { "type": "consumer", "reportId": 2, "usages": ["playPause", "volumeUp", "volumeDown", "mute"] },
            { "type": "pointer", "reportId": 3, "buttons": 2, "relativeAxes": ["x", "y"] }
          ]
        }
      },
      "ui": {
        "screens": [
          {
            "id": "deck",
            "title": "Deck",
            "layout": { "type": "grid", "columns": 3 },
            "widgets": [
              { "type": "button", "id": "apple", "label": "apple", "onTap": { "type": "macro", "macro": "typeApple" } },
              { "type": "button", "id": "mute", "label": "Mute", "onTap": { "type": "key", "key": "consumer.mute" } },
              { "type": "button", "id": "page2", "label": "Media", "onTap": { "type": "page", "screen": "media" } }
            ]
          },
          {
            "id": "media",
            "layout": { "type": "grid", "columns": 2 },
            "widgets": [
              { "type": "pointerPad", "id": "pad", "sensitivity": 1.4 },
              { "type": "led", "id": "caps", "led": "capsLock" }
            ]
          }
        ]
      },
      "macros": {
        "typeApple": { "steps": [ { "type": "type", "text": "apple", "keyDelayMs": 45, "jitterMs": 20 } ] }
      },
      "agent": { "defaultScope": "INVOKE_ONLY", "sensitiveControls": ["keyboard.winKey"] }
    }
    """.trimIndent()

    @Test
    fun `sample deck profile parses and validates cleanly`() {
        val profile = json.decodeFromString<Profile>(deckMini)
        val issues = ProfileValidator().validate(profile)
        assertEquals(emptyList<ProfileValidator.Issue>(), issues)
    }

    @Test
    fun `unknown consumer usage is an error`() {
        val profile = json.decodeFromString<Profile>(deckMini.replace("\"mute\"", "\"karaokeMode\""))
        val issues = ProfileValidator().validate(profile)
        assertTrue(issues.any { it.severity == ProfileValidator.Severity.ERROR && it.message.contains("karaokeMode") })
    }

    @Test
    fun `dangling macro reference is an error`() {
        val profile = json.decodeFromString<Profile>(deckMini.replace("\"macro\": \"typeApple\"", "\"macro\": \"ghost\""))
        val issues = ProfileValidator().validate(profile)
        assertTrue(issues.any { it.severity == ProfileValidator.Severity.ERROR && it.message.contains("ghost") })
    }

    @Test
    fun `untypable text is an error`() {
        val profile = json.decodeFromString<Profile>(deckMini.replace("\"text\": \"apple\"", "\"text\": \"héllo 你好\""))
        val issues = ProfileValidator().validate(profile)
        assertTrue(issues.any { it.severity == ProfileValidator.Severity.ERROR && it.message.contains("cannot type") })
    }

    @Test
    fun `unknown screen page reference is an error`() {
        val profile = json.decodeFromString<Profile>(deckMini.replace("\"screen\": \"media\"", "\"screen\": \"nowhere\""))
        val issues = ProfileValidator().validate(profile)
        assertTrue(issues.any { it.severity == ProfileValidator.Severity.ERROR && it.message.contains("nowhere") })
    }

    @Test
    fun `key binding to undeclared consumer key is an error`() {
        val profile = json.decodeFromString<Profile>(deckMini.replace("\"key\": \"consumer.mute\"", "\"key\": \"consumer.eject\""))
        val issues = ProfileValidator().validate(profile)
        assertTrue(issues.any { it.severity == ProfileValidator.Severity.ERROR && it.message.contains("consumer.eject") })
    }
}