package org.simpmusic.dj.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricSearchTest {
    @Test
    fun aYouTubeTitleIsCleanedToTheSongTitle() {
        assertEquals("Bombón Asesino", LyricQuery.of("Bombón Asesino (Video Oficial)", "Los Palmeras").title)
        assertEquals("Bombón Asesino", LyricQuery.of("Los Palmeras - Bombón Asesino [Official Music Video]", "Los Palmeras").title)
        assertEquals("Carita Triste", LyricQuery.of("Carita Triste | Cumbia 2024", "Q' Lokura").title)
        assertEquals("Me Enamoré", LyricQuery.of("Me Enamoré ft. Grupo X (Audio)", "Los Palmeras").title)
        // a live take keeps its "(En Vivo)" in the spelled variant: it has its own timing
        val live = LyricQuery.of("El Pirata (En Vivo)", "Grupo Green")
        assertEquals("El Pirata", live.title)
        assertTrue(live.titleVariants.contains("El Pirata (En Vivo)"))
        // a title that genuinely contains "con" is not cut
        assertEquals("Bailando con Lobos", LyricQuery.of("Bailando con Lobos", "Cabas").title)
    }

    @Test
    fun theArtistStringIsSplitIntoEveryArtist() {
        assertEquals(listOf("Q' Lokura", "Los Herrera"), LyricQuery.of("x", "Q' Lokura, Los Herrera").artists)
        assertEquals(listOf("Grupo Kual", "Ke Personajes"), LyricQuery.of("x", "Grupo Kual feat. Ke Personajes").artists)
        assertEquals(listOf("Los Palmeras"), LyricQuery.of("x", "Los Palmeras - Topic").artists)
    }

    @Test
    fun aSheetOfTheSameSongIsAcceptedAndAnotherSongIsNot() {
        val q = LyricQuery.of("Carita Triste (Video Oficial)", "Q' Lokura")
        val same = LyricMatch.Candidate("Carita Triste", "Q' Lokura, Los Herrera", 74.0, synced = true)
        val otherSongSameTitle = LyricMatch.Candidate("Carita Triste", "Carlitos Rossy feat. Randu", 190.0, synced = true)
        val sameLengthOtherTitle = LyricMatch.Candidate("Corazón Roto", "Q' Lokura", 75.0, synced = true)
        val plain = LyricMatch.Candidate("Carita Triste", "Q' Lokura", 74.0, synced = false)
        assertTrue(LyricMatch.score(same, q, 75.0) >= LyricMatch.ACCEPT)
        assertTrue(LyricMatch.score(otherSongSameTitle, q, 75.0) < 0)
        assertTrue(LyricMatch.score(sameLengthOtherTitle, q, 75.0) < 0)
        assertTrue(LyricMatch.score(plain, q, 75.0) < 0)
        // the accents and the case do not matter
        val accents = LyricMatch.Candidate("BOMBON ASESINO", "Los Palmeras", 201.0, synced = true)
        assertTrue(LyricMatch.score(accents, LyricQuery.of("Bombón Asesino", "Los Palmeras"), 200.0) >= LyricMatch.ACCEPT)
    }

    @Test
    fun aMusicVideoLongerThanTheAlbumTrackStillFindsItsSheet() {
        val q = LyricQuery.of("Gilda - No Me Arrepiento De Este Amor (Video Oficial)", "Gilda")
        val album = LyricMatch.Candidate("No Me Arrepiento De Este Amor", "Gilda", 200.0, synced = true)
        assertTrue(LyricMatch.score(album, q, 218.0) >= LyricMatch.ACCEPT)
        // the same gap with another artist is another song
        assertTrue(LyricMatch.score(album.copy(artist = "Otro"), q, 218.0) < 0)
        assertEquals("Corazón Mentiroso", LyricQuery.of("Karina - Corazón Mentiroso (Video Oficial)", "La Princesita Karina").title)
    }

    @Test
    fun withoutALengthTheArtistMustAgree() {
        val q = LyricQuery.of("Mentías", "Grupo Néctar")
        assertTrue(LyricMatch.score(LyricMatch.Candidate("Mentías", "Grupo Nectar", 200.0, true), q, null) >= LyricMatch.ACCEPT)
        assertTrue(LyricMatch.score(LyricMatch.Candidate("Mentías", "Dib", 200.0, true), q, null) < 0)
    }

    @Test
    fun lrcTextIsParsedWithRepeatedStampsAndWordStamps() {
        val lines = Lrc.parse("[ar: x]\n[00:12.50]Primera línea\n[00:20.00][01:05.3]Coro\n[00:30.120]<00:30.120>Pa <00:30.500>la<00:31.000>bra\n[00:40.00]")
        assertEquals(listOf(12_500L, 20_000L, 30_120L, 40_000L, 65_300L), lines.map { it.startMs })
        assertEquals("Coro", lines[1].text)
        assertEquals(listOf(30_120L, 30_500L, 31_000L), lines[2].wordStartsMs)
        assertEquals("Pa la bra", lines[2].text)
        assertTrue(LyricVocals.isInstrumental(lines[3].text))
        assertTrue(LyricVocals.isInstrumental("[Música]"))
        assertTrue(LyricVocals.isInstrumental("[Aplausos]"))
    }
}
