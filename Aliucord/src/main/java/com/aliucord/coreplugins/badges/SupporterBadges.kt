/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
 */

package com.aliucord.coreplugins.badges

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.aliucord.*
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.*
import com.aliucord.utils.DimenUtils.dp
import com.aliucord.utils.lazyField
import com.aliucord.views.Updater
import com.discord.databinding.UserProfileHeaderBadgeBinding
import com.discord.models.guild.Guild
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.widgets.channels.list.WidgetChannelsList
import com.discord.widgets.user.Badge
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.lytefast.flexinput.R

@Suppress("PrivatePropertyName")
internal class SupporterBadges : CorePlugin(MANIFEST) {
    /** Used for the badge in the guild channel list header */
    private val guildBadgeViewId = View.generateViewId()

    /** Badges API instance */
    private lateinit var badgesAPI: BadgesAPI

    /** Badges info that is populated upon plugin start */
    private var badges: BadgesInfo? = null

    // Cached fields
    private val f_badgesAdapter by lazyField<UserProfileHeaderView>("badgesAdapter")
    private val f_recyclerAdapterData by lazyField<SimpleRecyclerAdapter<*, *>>("data")
    private val f_badgeViewHolderBinding by lazyField<UserProfileHeaderView.BadgeViewHolder>("binding")

    @SuppressLint("SetTextI18n")
    override fun buildSettingsUI(view: ViewGroup): View {
        return Updater.create(view.context).apply {
            category("Personal Badges Configuration") {
                textInput("GitHub Repository URL", "Enter the URL to your GitHub repo containing badges.json") {
                    value = badgesAPI.getGitHubRepoUrl()
                    hint = "https://github.com/username/my-badges"
                    onChange { url ->
                        badgesAPI.setGitHubRepoUrl(url)
                        refreshBadges()
                    }
                }

                textInput("Your Discord User ID", "Enter your Discord user ID (right-click your profile and copy ID)") {
                    value = if (badgesAPI.getUserSnowflake() == 0L) "" else badgesAPI.getUserSnowflake().toString()
                    hint = "123456789012345678"
                    inputType = InputType.TYPE_CLASS_NUMBER
                    onChange { id ->
                        try {
                            val snowflake = if (id.isEmpty()) 0L else id.toLong()
                            badgesAPI.setUserSnowflake(snowflake)
                            refreshBadges()
                        } catch (e: NumberFormatException) {
                            Utils.showToast("Invalid user ID format")
                        }
                    }
                }

                switch("Enable Personal Badges", "Enable loading badges from your GitHub repository") {
                    isChecked = badgesAPI.isPersonalBadgesEnabled()
                    onCheckedChange { enabled ->
                        badgesAPI.setPersonalBadgesEnabled(enabled)
                        refreshBadges()
                    }
                }

                button("Auto-fill My User ID") {
                    onClick {
                        val currentUser = StoreStream.getUsers().me
                        if (currentUser != null) {
                            badgesAPI.setUserSnowflake(currentUser.id)
                            Utils.showToast("User ID set to: ${currentUser.id}")
                            // Refresh the settings UI
                            buildSettingsUI(view.parent as ViewGroup)
                        } else {
                            Utils.showToast("Could not get current user ID")
                        }
                    }
                }

                button("Refresh Badges Now") {
                    onClick {
                        refreshBadges()
                        Utils.showToast("Badges refreshed!")
                    }
                }

                text("How to use:") {
                    subtext = """
                        1. Create a GitHub repository
                        2. Add a file called 'badges.json' with your badge configuration
                        3. Enter the repository URL above
                        4. Enter your Discord user ID (or use auto-fill)
                        5. Enable personal badges
                        
                        Example badges.json format:
                        {
                          "badges": [
                            {
                              "url": "https://example.com/badge1.png",
                              "text": "My Custom Badge"
                            },
                            {
                              "url": "https://example.com/badge2.png", 
                              "text": "Another Badge"
                            }
                          ]
                        }
                    """.trimIndent()
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun start(context: Context) {
        badgesAPI = BadgesAPI(settings)
        
        Utils.threadPool.execute {
            badges = badgesAPI.getBadges()
        }

        // Add badges to the RecyclerView data for badges in the user profile header
        patcher.after<UserProfileHeaderView>("updateViewState", UserProfileHeaderViewModel.ViewState.Loaded::class.java)
        { (_, state: UserProfileHeaderViewModel.ViewState.Loaded) ->
            val userBadgesData = badges?.users?.get(state.user.id) ?: return@after
            val roleBadges = userBadgesData.roles?.mapNotNull(::getBadgeForRole) ?: emptyList()
            val customBadges = userBadgesData.custom?.map(::getBadgeForCustom) ?: emptyList()

            val adapter = f_badgesAdapter[this] as SimpleRecyclerAdapter<Badge, UserProfileHeaderView.BadgeViewHolder>
            val data = f_recyclerAdapterData[adapter] as MutableList<Badge>
            data.addAll(roleBadges)
            data.addAll(customBadges)
        }

        // Set image url for badge ImageViews
        patcher.after<UserProfileHeaderView.BadgeViewHolder>("bind", Badge::class.java)
        { (_, badge: Badge) ->
            // Image URL is smuggled through the objectType property
            val url = badge.objectType

            // Check that badge is ours
            if (badge.icon != 0 || url == null) return@after

            val binding = f_badgeViewHolderBinding[this] as UserProfileHeaderBadgeBinding
            val imageView = binding.b
            imageView.setCacheableImage(url)
        }

        // Add blank ImageView to the channels list
        patcher.after<WidgetChannelsList>("onViewBound", View::class.java) {
            val binding = WidgetChannelsList.`access$getBinding$p`(this)
            val toolbar = binding.g.parent as ViewGroup
            val imageView = ImageView(toolbar.context).apply {
                id = guildBadgeViewId
                setPadding(0, 0, 4.dp, 0)
            }

            if (toolbar.getChildAt(0).id != guildBadgeViewId)
                toolbar.addView(imageView, 0)
        }

        // Configure the channels list's newly added ImageView to show target guild badge
        patcher.after<WidgetChannelsList>("configureHeaderIcons", Guild::class.java, Boolean::class.javaPrimitiveType!!)
        { (_, guild: Guild?) ->
            val badgeData = guild?.id?.let { id -> badges?.guilds?.get(id) }

            if (this.view == null) return@after
            val binding = WidgetChannelsList.`access$getBinding$p`(this)
            val toolbar = binding.g.parent as ViewGroup

            toolbar.findViewById<ImageView>(guildBadgeViewId)?.apply {
                if (badgeData == null) visibility = View.GONE
                else {
                    visibility = View.VISIBLE
                    setCacheableImage(badgeData.url)
                    setOnClickListener { Utils.showToast(badgeData.text) }
                }
            }
        }
    }

    override fun stop(context: Context) {}

    private fun refreshBadges() {
        Utils.threadPool.execute {
            badges = badgesAPI.getBadges()
        }
    }

    private companion object {
        val MANIFEST = Manifest().apply {
            name = "SupporterBadges"
            description = "Show badges in the profiles of contributors and donors ♡\nNow supports custom badges from GitHub!"
        }

        val DEV_BADGE = Badge(R.e.ic_staff_badge_blurple_24dp, null, "Aliucord Developer", false, null)
        val DONOR_BADGE = Badge(0, null, "Aliucord Donor", false, "https://cdn.discordapp.com/emojis/859801776232202280.webp")
        val CONTRIB_BADGE = Badge(0, null, "Aliucord Contributor", false, "https://cdn.discordapp.com/emojis/886587553187246120.webp")

        fun getBadgeForRole(role: String): Badge? = when (role) {
            "dev" -> DEV_BADGE
            "donor" -> DONOR_BADGE
            "contributor" -> CONTRIB_BADGE
            else -> null
        }

        fun getBadgeForCustom(data: BadgeData): Badge =
            Badge(0, null, data.text, false, data.url)
    }
}