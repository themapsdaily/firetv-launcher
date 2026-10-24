package com.siddh.home;

/**
 * What the owner likes, for choosing what the streaming apps' shelves show. TMDB ids; edit freely.
 *
 * Likes: comedy (dark comedy, romcoms, sitcoms), documentaries, war, geopolitics, sci-fi.
 */
final class Taste {
    private Taste() {}

    /**
     * Watched or watching: never shown. Dark, Stranger Things, Chernobyl, The End of the F***ing
     * World, Money Heist, Game of Thrones, Friends, The Big Bang Theory, Severance, Senna (series),
     * Family Guy.
     */
    static final int[] SEEN_TV = {70523, 66732, 87108, 74577, 71446, 1399, 1668, 1418, 95396, 211039, 1434};

    /**
     * The Dark Knight trilogy, The Shawshank Redemption, Interstellar, Oppenheimer, Project Hail
     * Mary, The Dictator, Senna (2010).
     */
    static final int[] SEEN_MOVIE = {155, 272, 49026, 278, 157336, 872585, 687163, 76493, 58496};

    /** Watchlist, shown first wherever it streams: The Godfather, Inception, Dune, Dune: Part Two, Ford v Ferrari. */
    static final int[] WANT_MOVIE = {238, 27205, 438631, 693134, 359724};

    /**
     * TMDB keywords, any of which marks a title as "your kind of thing": dark comedy, black comedy,
     * sitcom, satire, mockumentary, deadpan, romantic comedy; time travel, alternate timeline,
     * parallel world, dystopia, space; heist, mafia, anti hero, prison, vigilante; government
     * conspiracy, politics, geopolitics, political thriller, cold war, world war ii, spy.
     */
    static final String KEYWORDS = "10123|383021|193171|8201|11800|212569|386551|4379|186760|33465|4565|9882"
            + "|10051|10391|2095|378|7002|181635|6078|214608|209817|2106|1956|470";

    /** Keywords never wanted: horror, supernatural horror, teen horror. */
    static final String NO_KEYWORDS = "315058|256183|309060";

    /** Genres for the looser picks (recent and Indian titles): comedy, documentary, war & politics or war, sci-fi, mystery or history. */
    static final String LIKE_TV = "35|99|10768|10765|9648", LIKE_MOVIE = "35|99|10752|878|36";

    /** Never: kids, news, reality, soap, talk, family; horror and TV films. */
    static final String NO_TV = "10762|10763|10764|10766|10767|10751", NO_MOVIE = "10751|27|10770";

    /** Sports followed, for the score pill and the Sports view. Names as ESPN spells them. */
    static final String[] CRICKET = {"India", "Royal Challengers Bengaluru"};
    static final String[] TENNIS = {"Elena Rybakina", "Maja Chwalinska"};

    /**
     * Spotify shelf, shown before India's charts: top artists and the albums behind the top tracks,
     * last 4 weeks and 6 months (Spotify, 2026-10-23). Name, artist (empty for an artist), 640 px art.
     */
    static final String[][] MUSIC = {
            {"Lana Del Rey", "", "https://i.scdn.co/image/ab6761610000e5ebb99cacf8acd5378206767261"},
            {"OK Computer", "Radiohead", "https://i.scdn.co/image/ab67616d0000b273c8b444df094279e70d0ed856"},
            {"Tame Impala", "", "https://i.scdn.co/image/ab6761610000e5ebe412a782245eb20d9626c601"},
            {"Favourite Worst Nightmare", "Arctic Monkeys", "https://i.scdn.co/image/ab67616d0000b273b1f8da74f225fa1225cdface"},
            {"Cool It Down", "Yeah Yeah Yeahs", "https://i.scdn.co/image/ab67616d0000b273667ca33e4b6d8e74b17a6529"},
            {"Submarine", "The Marías", "https://i.scdn.co/image/ab67616d0000b2738aa339341a0b0c813909c831"},
    };

    /**
     * Wikimedia Commons pictures the owner loved as backgrounds (picked 2026-10-23), a few of which
     * join the photo pool on every refresh: Tübingen at blue hour, Esztergom by night, Hong Kong at
     * night, Étretat in the evening, a heath at sunset, a village road in Hérault, the Gorges du
     * Tarn, and a stargazer under the Milky Way.
     */
    static final String[] PHOTOS_LOVED = {
            "File:Tübingen - Altstadt - Neckarfront - Ansicht von Neckarinsel in blauer Stunde (1).jpg",
            "File:Esztergom by night 01 - Simor János utca.jpg",
            "File:Victoria Harbour skyscrapers.jpg",
            "File:Manneporte en soir.jpg",
            "File:Haltern am See, Westruper Heide -- 2015 -- 7985-9.jpg",
            "File:Villeneuvette, Hérault 02.jpg",
            "File:01 Gorges du Tarn Roc des Hourtous.jpg",
            "File:018 Human looking at the stars during Perseids with the Milky Way in the background Photo by Giles Laurent.jpg",
    };
}
