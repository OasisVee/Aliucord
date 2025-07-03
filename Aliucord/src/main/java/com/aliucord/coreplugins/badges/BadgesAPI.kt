package com.aliucord.coreplugins.badges

import com.aliucord.*
import com.aliucord.api.SettingsAPI
import com.aliucord.settings.delegate
import kotlin.time.*

internal class BadgesAPI(private val settings: SettingsAPI) {
    private var SettingsAPI.cacheExpiration by settings.delegate(0L)
    private var SettingsAPI.cachedBadges by settings.delegate(BadgesInfo(emptyMap(), emptyMap()))
    private var SettingsAPI.personalCacheExpiration by settings.delegate(0L)
    private var SettingsAPI.cachedPersonalBadges by settings.delegate<List<BadgeData>>(emptyList())
    
    // Settings for personal badge configuration
    private var SettingsAPI.githubRepoUrl by settings.delegate("")
    private var SettingsAPI.personalBadgesEnabled by settings.delegate(false)
    private var SettingsAPI.userSnowflake by settings.delegate(0L)

    /**
     * Get cached badge data or re-fetch from the API if expired.
     */
    @Suppress("DEPRECATION")
    @OptIn(ExperimentalTime::class)
    fun getBadges(): BadgesInfo {
        val originalBadges = if (!isCacheExpired()) settings.cachedBadges else {
            val data = fetchBadges()
            if (data != null) {
                settings.cacheExpiration = System.currentTimeMillis() + 1.days.inWholeMilliseconds
                settings.cachedBadges = data
                data
            } else {
                // Failed to fetch; keep cache and try later
                settings.cacheExpiration = System.currentTimeMillis() + 6.hours.inWholeMilliseconds
                settings.cachedBadges
            }
        }

        // If personal badges are enabled, merge them in
        return if (settings.personalBadgesEnabled && settings.userSnowflake != 0L) {
            val personalBadges = getPersonalBadges()
            mergePersonalBadges(originalBadges, personalBadges)
        } else {
            originalBadges
        }
    }

    /**
     * Get personal badges from GitHub repo
     */
    @OptIn(ExperimentalTime::class)
    private fun getPersonalBadges(): List<BadgeData> {
        if (!isPersonalCacheExpired()) return settings.cachedPersonalBadges

        val personalBadges = fetchPersonalBadges()
        return if (personalBadges != null) {
            settings.personalCacheExpiration = System.currentTimeMillis() + 30.minutes.inWholeMilliseconds
            settings.cachedPersonalBadges = personalBadges
            personalBadges
        } else {
            // Failed to fetch; keep cache and try later
            settings.personalCacheExpiration = System.currentTimeMillis() + 5.minutes.inWholeMilliseconds
            settings.cachedPersonalBadges
        }
    }

    /**
     * Fetch personal badges from GitHub
     */
    private fun fetchPersonalBadges(): List<BadgeData>? {
        if (settings.githubRepoUrl.isEmpty()) return null
        
        return try {
            // Convert GitHub repo URL to raw content URL
            val rawUrl = convertToRawGitHubUrl(settings.githubRepoUrl)
            
            Http.Request(rawUrl)
                .setHeader("User-Agent", "Aliucord/${BuildConfig.VERSION}")
                .execute()
                .json(PersonalBadgesConfig::class.java)
                .badges
        } catch (e: Exception) {
            Logger("BadgesAPI").error("Failed to fetch personal badges from GitHub!", e)
            null
        }
    }

    /**
     * Convert GitHub repo URL to raw content URL
     * Examples:
     * https://github.com/user/repo/blob/main/badges.json -> https://raw.githubusercontent.com/user/repo/main/badges.json
     * https://github.com/user/repo -> https://raw.githubusercontent.com/user/repo/main/badges.json
     */
    private fun convertToRawGitHubUrl(repoUrl: String): String {
        val cleanUrl = repoUrl.trim().removeSuffix("/")
        
        return when {
            cleanUrl.contains("raw.githubusercontent.com") -> cleanUrl
            cleanUrl.contains("/blob/") -> {
                cleanUrl.replace("github.com", "raw.githubusercontent.com")
                    .replace("/blob/", "/")
            }
            cleanUrl.contains("github.com") -> {
                val repoPath = cleanUrl.substringAfter("github.com/")
                "https://raw.githubusercontent.com/$repoPath/main/badges.json"
            }
            else -> cleanUrl
        }
    }

    /**
     * Merge personal badges into the original badges info
     */
    private fun mergePersonalBadges(original: BadgesInfo, personalBadges: List<BadgeData>): BadgesInfo {
        val userSnowflake = settings.userSnowflake
        val existingUserData = original.users[userSnowflake]
        
        val updatedUserData = BadgesUserData(
            roles = existingUserData?.roles,
            custom = (existingUserData?.custom ?: emptyList()) + personalBadges
        )
        
        val updatedUsers = original.users.toMutableMap()
        updatedUsers[userSnowflake] = updatedUserData
        
        return BadgesInfo(
            guilds = original.guilds,
            users = updatedUsers
        )
    }

    /**
     * Fetch badge data from the API directly.
     */
    private fun fetchBadges(): BadgesInfo? {
        return try {
            Http.Request("https://aliucord.com/files/badges/data.json")
                .setHeader("User-Agent", "Aliucord/${BuildConfig.VERSION}")
                .execute()
                .json(BadgesInfo::class.java)
        } catch (e: Exception) {
            Logger("BadgesAPI").error("Failed to fetch supporter badges!", e)
            null
        }
    }

    private fun isCacheExpired(): Boolean =
        settings.cacheExpiration <= System.currentTimeMillis()

    private fun isPersonalCacheExpired(): Boolean =
        settings.personalCacheExpiration <= System.currentTimeMillis()

    // Configuration methods
    fun setGitHubRepoUrl(url: String) {
        settings.githubRepoUrl = url
        // Clear cache to force refresh
        settings.personalCacheExpiration = 0L
    }

    fun setPersonalBadgesEnabled(enabled: Boolean) {
        settings.personalBadgesEnabled = enabled
    }

    fun setUserSnowflake(snowflake: Long) {
        settings.userSnowflake = snowflake
    }

    fun getGitHubRepoUrl(): String = settings.githubRepoUrl
    fun isPersonalBadgesEnabled(): Boolean = settings.personalBadgesEnabled
    fun getUserSnowflake(): Long = settings.userSnowflake
}

private typealias Snowflake = Long
private typealias BadgeName = String

internal data class BadgesInfo(
    val guilds: Map<Snowflake, BadgeData>,
    val users: Map<Snowflake, BadgesUserData>,
)

internal data class BadgesUserData(
    val roles: List<BadgeName>?,
    val custom: List<BadgeData>?,
)

internal data class BadgeData(
    val url: String,
    val text: String,
)

internal data class PersonalBadgesConfig(
    val badges: List<BadgeData>
)