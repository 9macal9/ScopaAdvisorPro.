package com.example.scopaadvisor

object AutoState {
    @Volatile var hand: List<Card> = emptyList()
    @Volatile var table: List<Card> = emptyList()
    @Volatile var seen: Set<Card> = emptySet()
    @Volatile var status: String = "In attesa"
    @Volatile var lastSuggestion: String = ""
}
