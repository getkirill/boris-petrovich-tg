package dev.kraskaska.boris

import kotlinx.datetime.Instant


class Config(
    val chatId: Long,
    var generationChance: Float,
    var silenceUntil: Instant?,
    var contextWindow: Int
)