package com.karal.studio.data.repository

/** Stable keys for the settings table. Values are always stored as strings. */
object SettingsKeys {
    const val THEME = "theme"
    const val LANGUAGE = "language"
    const val NOTIFICATIONS = "notifications_enabled"
    const val AUTO_START = "auto_start_enabled"
    const val AUTO_BACKUP = "auto_backup_enabled"
    const val BACKUP_INTERVAL_HOURS = "backup_interval_hours"
    const val LOG_RETENTION_DAYS = "log_retention_days"
    const val AI_ENDPOINT = "ai_endpoint"
    const val AI_MODEL = "ai_model"
    const val AI_KEY_ALIAS = "ai_key_alias"
    const val DIAGNOSTICS = "diagnostics_enabled"

    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_DARK = "DARK"
    const val THEME_LIGHT = "LIGHT"
    const val LANGUAGE_HI = "hi"
    const val LANGUAGE_EN = "en"
    const val AI_KEY_SECRET_ALIAS = "karal_ai_provider_key"
}
