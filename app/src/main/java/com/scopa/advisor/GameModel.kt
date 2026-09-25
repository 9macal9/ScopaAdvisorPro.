package com.example.scopaadvisor

import kotlin.math.max
import kotlin.random.Random

data class Card(val suit: Int, val value: Int) {
    override fun toString(): String = "${valueLabel(value)} ${suitLabel(suit)}"
}

enum class Zone { UNKNOWN, HAND, TABLE, MINE, OPP }

data class SimState(
    val handMe: MutableList<Card>,
    val handOpp: MutableList<Card>,
    val table: MutableList<Card>,
    val capMe: MutableList<Card>,
    val capOpp: MutableList<Card>,
    val deck: MutableList<Card>,
    var scopeMe: Int = 0,
    var scopeOpp: Int = 0,
    var lastCapturer: Int = -1
)

data class Move(val card: Card, val capture: List<Card>)
data class Candidate(val move: Move, val winPct: Double, val avgMargin: Double)

fun suitLabel(s: Int) = arrayOf("Denari", "Coppe", "Spade", "Bastoni")[s]
fun suitShort(s: Int) = arrayOf("D", "C", "S", "B")[s]
fun valueLabel(v: Int) = when (v) { 1 -> "A"; 8 -> "F"; 9 -> "C"; 10 -> "R"; else -> v.toString() }

object ScopaEngine {
    val allCards = (0..3).flatMap { s -> (1..10).map { v -> Card(s, v) } }

    fun analyze(zones: Map<Card, Zone>, oppHandCount: Int, runs: Int = 500): List<Candidate> {
        val hand = allCards.filter { zones[it] == Zone.HAND }
        val table = allCards.filter { zones[it] == Zone.TABLE }
        val unknown = allCards.filter { zones[it] == Zone.UNKNOWN }
        val oppN = minOf(oppHandCount, unknown.size)
        val candidates = mutableListOf<Candidate>()
        for (card in hand) {
            val legalCaps = legalCaptures(card, table)
            val options = if (legalCaps.isEmpty()) listOf(emptyList()) else legalCaps
            for (cap in options) {
                val move = Move(card, cap)
                val stats = simulateCandidate(zones, move, oppN, runs)
                candidates += Candidate(move, stats.first, stats.second)
            }
        }
        return candidates.groupBy { it.move.card }
            .mapValues { (_, v) -> v.maxBy { it.winPct * 100 + it.avgMargin } }
            .values.sortedByDescending { it.winPct }
    }

    private fun simulateCandidate(zones: Map<Card, Zone>, first: Move, oppHandCount: Int, runs: Int): Pair<Double, Double> {
        var wins = 0.0
        var margin = 0.0
        repeat(runs) {
            val handMe = allCards.filter { zones[it] == Zone.HAND }.toMutableList()
            val table = allCards.filter { zones[it] == Zone.TABLE }.toMutableList()
            val capMe = allCards.filter { zones[it] == Zone.MINE }.toMutableList()
            val capOpp = allCards.filter { zones[it] == Zone.OPP }.toMutableList()
            val unseen = allCards.filter { zones[it] == Zone.UNKNOWN }.shuffled().toMutableList()
            val oppHand = mutableListOf<Card>()
            repeat(minOf(oppHandCount, unseen.size)) { oppHand += unseen.removeAt(unseen.lastIndex) }
            val s = SimState(handMe, oppHand, table, capMe, capOpp, unseen)
            applyMove(s, 0, first, countScope = true)
            var turn = 1
            var guard = 0
            while (guard++ < 80) {
                if (s.handMe.isEmpty() && s.handOpp.isEmpty()) {
                    if (s.deck.isEmpty()) break
                    repeat(3) { if (s.deck.isNotEmpty()) s.handMe += s.deck.removeAt(s.deck.lastIndex) }
                    repeat(3) { if (s.deck.isNotEmpty()) s.handOpp += s.deck.removeAt(s.deck.lastIndex) }
                }
                val h = if (turn == 0) s.handMe else s.handOpp
                if (h.isEmpty()) { turn = 1 - turn; continue }
                val move = chooseGreedyMove(h, s.table)
                val finalPlay = s.deck.isEmpty() && s.handMe.size + s.handOpp.size == 1
                applyMove(s, turn, move, countScope = !finalPlay)
                turn = 1 - turn
            }
            if (s.table.isNotEmpty() && s.lastCapturer >= 0) {
                if (s.lastCapturer == 0) s.capMe.addAll(s.table) else s.capOpp.addAll(s.table)
                s.table.clear()
            }
            val a = score(s.capMe, s.scopeMe, s.capOpp)
            val b = score(s.capOpp, s.scopeOpp, s.capMe)
            if (a > b) wins += 1.0 else if (a == b) wins += 0.5
            margin += (a - b)
        }
        return (wins / runs) to (margin / runs)
    }

    private fun chooseGreedyMove(hand: List<Card>, table: List<Card>): Move {
        val all = hand.flatMap { c ->
            val caps = legalCaptures(c, table)
            if (caps.isEmpty()) listOf(Move(c, emptyList())) else caps.map { Move(c, it) }
        }
        return all.maxBy { moveHeuristic(it, table) + Random.nextDouble(0.0, 0.05) }
    }

    private fun moveHeuristic(m: Move, table: List<Card>): Double {
        if (m.capture.isEmpty()) {
            var risk = 0.0
            if (m.card.suit == 0) risk += 0.8
            if (m.card.value == 7) risk += 1.2
            return -risk
        }
        var h = m.capture.size.toDouble()
        if (m.capture.size == table.size) h += 6.0
        val won = m.capture + m.card
        if (won.any { it.suit == 0 && it.value == 7 }) h += 5.0
        h += won.count { it.suit == 0 } * 0.8
        h += won.count { it.value == 7 } * 0.6
        return h
    }

    private fun applyMove(s: SimState, player: Int, m: Move, countScope: Boolean) {
        val hand = if (player == 0) s.handMe else s.handOpp
        val caps = if (player == 0) s.capMe else s.capOpp
        hand.remove(m.card)
        if (m.capture.isNotEmpty()) {
            m.capture.forEach { s.table.remove(it) }
            caps += m.card
            caps += m.capture
            s.lastCapturer = player
            if (s.table.isEmpty() && countScope) {
                if (player == 0) s.scopeMe++ else s.scopeOpp++
            }
        } else s.table += m.card
    }

    fun legalCaptures(card: Card, table: List<Card>): List<List<Card>> {
        val equal = table.filter { it.value == card.value }
        if (equal.isNotEmpty()) return equal.map { listOf(it) }
        val out = mutableListOf<List<Card>>()
        val n = table.size
        for (mask in 1 until (1 shl n)) {
            val set = mutableListOf<Card>()
            var sum = 0
            for (i in 0 until n) if ((mask and (1 shl i)) != 0) { set += table[i]; sum += table[i].value }
            if (sum == card.value) out += set
        }
        return out.distinctBy { it.sortedWith(compareBy<Card> { c -> c.suit }.thenBy { c -> c.value }).joinToString { c -> "${c.suit}-${c.value}" } }
    }

    private fun score(mine: List<Card>, scopes: Int, other: List<Card>): Int {
        var p = scopes
        if (mine.size > other.size) p++
        val myD = mine.count { it.suit == 0 }; val otD = other.count { it.suit == 0 }
        if (myD > otD) p++
        if (mine.any { it.suit == 0 && it.value == 7 }) p++
        val mp = primiera(mine); val op = primiera(other)
        if (mp > 0 && mp > op) p++
        return p
    }

    private fun primiera(cards: List<Card>): Int {
        val pv = mapOf(7 to 21, 6 to 18, 1 to 16, 5 to 15, 4 to 14, 3 to 13, 2 to 12, 8 to 10, 9 to 10, 10 to 10)
        var sum = 0
        for (s in 0..3) {
            val best = cards.filter { it.suit == s }.maxOfOrNull { pv[it.value] ?: 0 } ?: return 0
            sum += best
        }
        return sum
    }
}
