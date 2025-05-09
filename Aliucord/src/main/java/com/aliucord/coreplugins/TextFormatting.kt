/*
 * This file is part of Aliucord, an Android Discord client mod.
 * Copyright (c) 2021 Juby210 & Vendicated
 * Licensed under the Open Software License version 3.0
*/

package com.aliucord.coreplugins

import android.content.Context
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.*
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import com.aliucord.Logger
import com.aliucord.entities.CorePlugin
import com.aliucord.patcher.*
import com.aliucord.utils.DimenUtils
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.ParseSpec
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.parser.Rule
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.textprocessing.*
import com.lytefast.flexinput.R
import java.lang.reflect.Field
import java.util.regex.Matcher
import java.util.regex.Pattern

internal class TextFormatting : CorePlugin(Manifest("TextFormatting")) {
    private val logger = Logger("TextFormatting")
    private lateinit var rulesField: Field

    init {
        manifest.description = "Adds bullet points, headers, and subtext formatting to messages"
    }

    override val isRequired = true

    override fun load(context: Context) {
        // No implementation needed
    }

    override fun start(context: Context) {
        try {
            rulesField = Parser::class.java.getDeclaredField("rules")
            rulesField.isAccessible = true
        } catch (e: NoSuchFieldException) {
            logger.error("Failed to get rules field", e)
        }

        patcher.patch(
            DiscordParser::class.java.getMethod(
                "parseChannelMessage",
                Context::class.java,
                String::class.java,
                MessageRenderContext::class.java,
                MessagePreprocessor::class.java,
                DiscordParser.ParserOptions::class.java,
                Boolean::class.java
            ),
            PreHook { callFrame ->
                try {
                    val ctx = callFrame.args[0] as Context
                    val parser = DiscordParser.createParser(true, true, true, false, false)
                    val str = callFrame.args[1] as? String ?: ""
                    
                    @Suppress("UNCHECKED_CAST")
                    val rules = rulesField.get(parser) as ArrayList<Rule<MessageRenderContext, out Node<MessageRenderContext>, MessageParseState>>
                    
                    rules.add(0, HeaderRule())
                    rules.add(0, SubtextRule(ctx))
                    rules.add(0, BulletPointRule(ctx))
                    
                    rulesField.set(parser, rules)
                    
                    val parsed = Parser.parse(parser, str, MessageParseState.Companion.getInitialState())
                    (callFrame.args[3] as MessagePreprocessor).process(parsed)
                    
                    if (callFrame.args[5] as Boolean) {
                        parsed.add(EditedMessageNode(ctx))
                    }
                    
                    parsed.add(ZeroSpaceWidthNode())
                    callFrame.result = AstRenderer.render(parsed, callFrame.args[2] as MessageRenderContext)
                } catch (e: Throwable) {
                    logger.error("Error patching parser", e)
                }
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    // Header node
    internal class HeaderNode<MessageRenderContext>(private val headerSize: Int) : Node<MessageRenderContext>() {
        override fun render(builder: SpannableStringBuilder, renderContext: MessageRenderContext) {
            val length = builder.length()
            for (n in children) {
                n.render(builder, renderContext)
            }
            
            // Apply size based on header level
            when (headerSize) {
                1 -> builder.setSpan(RelativeSizeSpan(2.0f), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                2 -> builder.setSpan(RelativeSizeSpan(1.5f), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                3 -> builder.setSpan(RelativeSizeSpan(1.25f), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            // Add bold styling
            builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // Header rule
    internal class HeaderRule : Rule.BlockRule<MessageRenderContext, HeaderNode<MessageRenderContext>, MessageParseState>(
        Pattern.compile("^\\s*(##?#?)\\s+(.+)(?=\\n|$)")
    ) {
        override fun parse(
            matcher: Matcher,
            parser: Parser<MessageRenderContext, out HeaderNode<MessageRenderContext>, MessageParseState>,
            state: MessageParseState
        ): ParseSpec<MessageRenderContext, MessageParseState> {
            val headerNode = HeaderNode<MessageRenderContext>(matcher.group(1).length)
            return ParseSpec(headerNode, state, matcher.start(2), matcher.end(2))
        }
    }

    // Bullet point node
    internal class BulletPointNode<MessageRenderContext>(private val context: Context) : Node.a<MessageRenderContext>() {
        override fun render(builder: SpannableStringBuilder, renderContext: MessageRenderContext) {
            val length = builder.length()
            super.render(builder, renderContext)

            val greyColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted)
            val span = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                BulletSpan(DimenUtils.dpToPx(8), greyColor, 6)
            } else {
                BulletSpan(DimenUtils.dpToPx(8), greyColor)
            }

            builder.setSpan(span, length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(android.graphics.Typeface.NORMAL), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // Bullet point rule
    internal class BulletPointRule(private val context: Context) : Rule.BlockRule<MessageRenderContext, BulletPointNode<MessageRenderContext>, MessageParseState>(
        Pattern.compile("^\\s*([*-])\\s+(.+)(?=\\n|$)")
    ) {
        override fun parse(
            matcher: Matcher,
            parser: Parser<MessageRenderContext, out BulletPointNode<MessageRenderContext>, MessageParseState>,
            state: MessageParseState
        ): ParseSpec<MessageRenderContext, MessageParseState> {
            return ParseSpec(BulletPointNode<MessageRenderContext>(context), state, matcher.start(2), matcher.end(2))
        }
    }

    // Subtext node
    internal class SubtextNode<MessageRenderContext>(private val context: Context) : Node.a<MessageRenderContext>() {
        override fun render(builder: SpannableStringBuilder, renderContext: MessageRenderContext) {
            val length = builder.length()
            super.render(builder, renderContext)

            builder.setSpan(RelativeSizeSpan(0.85f), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)

            val greyColor = ColorCompat.getThemedColor(context, R.b.colorTextMuted)
            builder.setSpan(ForegroundColorSpan(greyColor), length, builder.length(), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // Subtext rule
    internal class SubtextRule(private val context: Context) : Rule.BlockRule<MessageRenderContext, SubtextNode<MessageRenderContext>, MessageParseState>(
        Pattern.compile("^\\s*(-#)\\s+(.+)(?=\\n|$)")
    ) {
        override fun parse(
            matcher: Matcher,
            parser: Parser<MessageRenderContext, out SubtextNode<MessageRenderContext>, MessageParseState>,
            state: MessageParseState
        ): ParseSpec<MessageRenderContext, MessageParseState> {
            return ParseSpec(SubtextNode<MessageRenderContext>(context), state, matcher.start(2), matcher.end(2))
        }
    }
}