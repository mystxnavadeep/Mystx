package com.mystx.app.manager

import android.content.Context
import android.content.SharedPreferences
import com.mystx.app.model.Command
import com.mystx.app.model.CommandCategory
import com.mystx.app.model.CommandMeta
import com.mystx.app.model.RichCommand
import com.mystx.app.model.StudioDefaults
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistence and state management layer for Command Studio.
 *
 * Wraps [CommandManager] and stores per-command Studio metadata (name, category,
 * model/temperature overrides, enabled state) in SharedPreferences.
 */
class CommandStudioStore(
    context: Context,
    val commandManager: CommandManager
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("command_studio", Context.MODE_PRIVATE)

    companion object {
        const val MIN_TEMPERATURE = 0f
        const val MAX_TEMPERATURE = 2f

        enum class TriggerError { NONE, EMPTY, NOT_PREFIXED, TOO_SHORT, TOO_LONG }

        fun validateTrigger(trigger: String, prefix: String): TriggerError {
            val trimmed = trigger.trim()
            return when {
                trimmed.isEmpty() -> TriggerError.EMPTY
                !trimmed.startsWith(prefix) -> TriggerError.NOT_PREFIXED
                trimmed.length <= prefix.length || trimmed.substring(prefix.length).isBlank() -> TriggerError.TOO_SHORT
                trimmed.length > CommandManager.MAX_TRIGGER_LENGTH -> TriggerError.TOO_LONG
                else -> TriggerError.NONE
            }
        }
    }

    /**
     * All commands merged with their persisted Command Studio metadata.
     */
    fun getRichCommands(): List<RichCommand> {
        val raw = commandManager.getCommands()
        val result = raw.map { cmd ->
            getRichForCommand(cmd)
        }
        return result.sortedByDescending { it.trigger.length }
    }

    /**
     * Converts a raw [Command] into a [RichCommand], applying stored metadata if present.
     */
    fun getRichForCommand(cmd: Command): RichCommand {
        val metaKey = metaKeyFor(RichCommand.keyFor(cmd.trigger))
        val meta = prefs.getString(metaKey, null)?.let { CommandMeta.fromJson(it) }
        val base = defaultRich(cmd)
        return if (meta == null) base else base.withMeta(meta)
    }

    private fun defaultRich(cmd: Command): RichCommand {
        val base = RichCommand.fromCommand(cmd)
        return if (cmd.isBuiltIn) base.copy(
            name = StudioDefaults.nameFor(cmd),
            category = StudioDefaults.categoryFor(cmd)
        ) else base
    }

    /**
     * Finds a matching command for the input text, resolving metadata and overrides.
     */
    fun findRich(text: String): RichCommand? {
        val cmd = commandManager.findCommand(text) ?: return null
        return getRichForCommand(cmd)
    }

    fun saveMeta(command: RichCommand) {
        val cleanTrigger = command.trigger.trim()
        val keyId = RichCommand.keyFor(cleanTrigger).ifEmpty { command.id }
        val safeTemp = command.temperature
            ?.takeIf { !it.isNaN() }
            ?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
        val sanitized = command.copy(trigger = cleanTrigger, temperature = safeTemp)
        prefs.edit().putString(metaKeyFor(keyId), sanitized.toMeta().toJson()).apply()
    }

    fun removeMeta(id: String) {
        prefs.edit().remove(metaKeyFor(id)).apply()
    }

    private fun metaKeyFor(id: String) = "meta_$id"

    /**
     * Creates or updates a custom command.
     * Returns the stored trigger on success, or null on validation/storage failure.
     */
    fun saveCustom(command: RichCommand, replacing: String = command.trigger): String? {
        val cleanTrigger = command.trigger.trim()
        val cleanReplacing = replacing.trim()
        val prompt = command.promptTemplate
        val prefix = commandManager.getTriggerPrefix()

        if (validateTrigger(cleanTrigger, prefix) != TriggerError.NONE) {
            return null
        }
        if (!CommandManager.isValidCommand(cleanTrigger, prompt, prefix)) {
            return null
        }
        if (command.temperature != null && command.temperature.isNaN()) {
            return null
        }
        val clampedTemp = command.temperature?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)

        val existingAll = getRichCommands()
        val collidesWithBuiltIn = existingAll.any {
            it.isBuiltIn && (
                it.trigger.equals(cleanTrigger, ignoreCase = true) ||
                    it.id.equals(RichCommand.keyFor(cleanTrigger), ignoreCase = true)
            )
        }
        if (collidesWithBuiltIn) {
            return null
        }

        val existingCustom = existingAll.filter { !it.isBuiltIn }
        val isUpdatingExisting = existingCustom.any {
            it.trigger.equals(cleanReplacing, ignoreCase = true) ||
                it.trigger.equals(cleanTrigger, ignoreCase = true)
        }
        if (!isUpdatingExisting && existingCustom.size >= CommandManager.MAX_CUSTOM_COMMANDS) {
            return null
        }

        val stored = Command(cleanTrigger, prompt, false, command.type)
        val ok = commandManager.saveCustomCommand(stored, cleanReplacing)
        if (ok) {
            val oldId = RichCommand.keyFor(cleanReplacing)
            val newId = RichCommand.keyFor(cleanTrigger)
            if (oldId.isNotEmpty() && oldId != newId) {
                removeMeta(oldId)
            }
            saveMeta(command.copy(id = newId, trigger = cleanTrigger, temperature = clampedTemp))
        }
        return if (ok) cleanTrigger else null
    }

    /**
     * Checks whether a trigger is already taken by another command (case-insensitive).
     */
    fun isTriggerTaken(trigger: String, excludingId: String?): Boolean {
        val trimmed = trigger.trim()
        val targetKey = RichCommand.keyFor(trimmed)
        val normalizedExcluding = excludingId?.let {
            RichCommand.keyFor(it).ifEmpty { it.lowercase(Locale.ROOT) }
        }
        return getRichCommands().any { cmd ->
            val isExcluded = excludingId != null && (
                cmd.id.equals(excludingId, ignoreCase = true) ||
                    cmd.trigger.equals(excludingId, ignoreCase = true) ||
                    (normalizedExcluding != null && cmd.id.equals(normalizedExcluding, ignoreCase = true))
            )
            !isExcluded && (
                cmd.trigger.equals(trimmed, ignoreCase = true) ||
                    (targetKey.isNotEmpty() && cmd.id.equals(targetKey, ignoreCase = true))
            )
        }
    }

    /**
     * Deletes a custom command. Built-ins are protected and cannot be deleted.
     */
    fun deleteCustom(id: String): Boolean {
        val target = getRichCommands().firstOrNull {
            it.id == id || it.id == RichCommand.keyFor(id) || it.trigger == id
        } ?: return false
        if (target.isBuiltIn) return false
        commandManager.removeCustomCommand(target.trigger)
        removeMeta(target.id)
        return true
    }

    /**
     * Restores a built-in command to its default presentation and behavior.
     */
    fun resetBuiltIn(id: String): Boolean {
        val target = getRichCommands().firstOrNull {
            it.id == id || it.id == RichCommand.keyFor(id) || it.trigger == id
        } ?: return false
        if (!target.isBuiltIn) return false
        removeMeta(target.id)
        return true
    }

    /**
     * Toggles whether a command is active.
     */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val target = getRichCommands().firstOrNull {
            it.id == id || it.id == RichCommand.keyFor(id) || it.trigger == id
        } ?: return false
        saveMeta(target.copy(enabled = enabled))
        return true
    }

    /**
     * Exports a v2 backup JSON object containing both [CommandManager.exportCommands]
     * and all persisted `command_studio` metadata.
     */
    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", 2)
        val commandsArr = try {
            JSONArray(commandManager.exportCommands())
        } catch (_: Exception) {
            JSONArray()
        }
        root.put("commands", commandsArr)
        val metaObj = JSONObject()
        for ((key, value) in prefs.all) {
            if (key.startsWith("meta_") && value is String) {
                metaObj.put(key, value)
            }
        }
        root.put("studioMeta", metaObj)
        return root.toString()
    }

    /**
     * Imports commands and metadata from either a v2 backup JSON object or a legacy
     * v1 JSON array (delegated to [CommandManager.importCommands]).
     *
     * Returns the number of imported commands.
     */
    fun importBackupJson(json: String): Int {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return 0
        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                if (arr.length() == 0) return 0
                if (!commandManager.importCommands(trimmed)) return 0
                countValidEntries(arr)
            } else {
                val root = JSONObject(trimmed)
                val commandsArr = root.optJSONArray("commands") ?: JSONArray()
                val importedCount = if (commandsArr.length() == 0) {
                    0
                } else {
                    if (!commandManager.importCommands(commandsArr.toString())) return 0
                    countValidEntries(commandsArr)
                }
                val metaObj = root.optJSONObject("studioMeta")
                if (metaObj != null) {
                    val editor = prefs.edit()
                    val keys = metaObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!k.startsWith("meta_")) continue
                        val rawVal = when (val v = metaObj.opt(k)) {
                            is String -> v
                            is JSONObject -> v.toString()
                            else -> null
                        }
                        if (!rawVal.isNullOrBlank()) {
                            val parsedMeta = CommandMeta.fromJson(rawVal)
                            if (parsedMeta != null) {
                                editor.putString(k, parsedMeta.toJson())
                            }
                        }
                    }
                    editor.apply()
                }
                importedCount
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun countValidEntries(arr: JSONArray): Int {
        val prefix = commandManager.getTriggerPrefix()
        val seen = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            if (seen.size >= CommandManager.MAX_CUSTOM_COMMANDS) break
            val obj = arr.optJSONObject(i) ?: continue
            var trigger = obj.optString("trigger", "").trim()
            val prompt = obj.optString("prompt", "").take(CommandManager.MAX_PROMPT_LENGTH)
            if (trigger.isEmpty() || prompt.isBlank()) continue
            if (!trigger.startsWith(prefix)) {
                val stripped = if (!trigger[0].isLetterOrDigit()) trigger.substring(1) else trigger
                trigger = prefix + stripped
            }
            trigger = trigger.take(CommandManager.MAX_TRIGGER_LENGTH)
            if (trigger.length <= prefix.length || trigger.substring(prefix.length).isBlank()) continue
            seen.add(trigger)
        }
        return seen.size
    }

    /**
     * Pure search and category filter.
     */
    fun filter(
        commands: List<RichCommand>,
        query: String,
        category: CommandCategory?
    ): List<RichCommand> {
        val q = query.trim().lowercase()
        return commands.filter { cmd ->
            val matchCategory = category == null || category == CommandCategory.ALL || cmd.category == category
            val matchQuery = q.isEmpty() ||
                cmd.name.lowercase().contains(q) ||
                cmd.trigger.lowercase().contains(q) ||
                cmd.category.name.lowercase().contains(q) ||
                cmd.category.displayName.lowercase().contains(q)
            matchCategory && matchQuery
        }
    }
}
