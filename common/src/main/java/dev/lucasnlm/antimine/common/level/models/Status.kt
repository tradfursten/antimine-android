package dev.lucasnlm.antimine.common.level.models

sealed class Status {
    object PreGame : Status()

    object Running : Status()

    class Over(
        val time: Long = 0L,
        val score: Score? = null,
    ) : Status()
}
