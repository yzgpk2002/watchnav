package com.watchnav.app

import android.content.Context
import java.util.Locale

enum class AppLanguage(val tag: String) {
    CHINESE("zh-CN"),
    ENGLISH("en"),
}

object AppLanguageController {
    private const val PREFERENCES = "display_preferences"
    private const val KEY_LANGUAGE = "app_language"

    fun current(context: Context): AppLanguage {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        return AppLanguage.entries.firstOrNull { it.name == stored }
            ?: if (Locale.getDefault().language == Locale.CHINESE.language) AppLanguage.CHINESE else AppLanguage.ENGLISH
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(current(context).tag)
        val configuration = context.resources.configuration
        if (configuration.locales[0] == locale) return context
        Locale.setDefault(locale)
        return context.createConfigurationContext(android.content.res.Configuration(configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        })
    }

    fun toggle(context: Context): AppLanguage {
        val next = if (current(context) == AppLanguage.CHINESE) AppLanguage.ENGLISH else AppLanguage.CHINESE
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, next.name)
            .apply()
        return next
    }
}
