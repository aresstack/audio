package com.aresstack.audio.studio.figure;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The figures the studio brings along: a musician for every family of instruments, so that every
 * track has a figure on the stage from the first moment, before the AI has drawn one. They are
 * SVG like the drawn ones, written here, and differ from track to track in skin, hair and
 * clothes, the same for the same track every time.
 */
public final class HouseFigures {
    /** The families, in the order their words are looked for: a "bass drum" is drums, a "bass guitar" a guitar. */
    private static final String[][] FAMILIES = {
            {"drums", "drum", "schlag", "percussion", "perkussion", "trommel", "pauke", "timpani", "kit", "becken", "cymbal"},
            {"wind", "flute", "floete", "flöte", "oboe", "klarinet", "clarinet", "sax", "fagott", "bassoon", "recorder", "piccolo", "woodwind", "holzbl"},
            {"brass", "trumpet", "trompete", "horn", "trombone", "posaune", "tuba", "brass", "blech"},
            {"guitar", "guitar", "gitarre", "banjo", "ukulele", "mandolin", "laute", "lute"},
            {"keys", "piano", "klavier", "keys", "organ", "orgel", "cembalo", "harpsichord", "rhodes", "music-box", "music box", "spieluhr", "celesta", "fluegel", "flügel", "akkordeon", "accordion"},
            {"synth", "synth", "pad", "lead", "arp", "sequencer", "elektron", "electron"},
            {"bass", "bass"},
            {"strings", "violin", "viola", "geige", "cello", "string", "streich", "bratsche", "harp", "harfe", "fiddle", "harmony"},
            {"voice", "vocal", "voice", "choir", "chor", "stimme", "gesang", "sing", "sopran", "tenor", "alt"}};
    public static final String DEFAULT_SKIN = "#E8B48C,#C98D62,#F1C9A5,#8D5A3B,#D9A273";
    public static final String DEFAULT_HAIR = "#3B2A20,#151515,#7A4A22,#C9A04A,#5A5A66,#8A2F2F";
    private static String skins = DEFAULT_SKIN;
    private static String hairs = DEFAULT_HAIR;

    /** Sets the colours of skin and hair the figures come in (#RRGGBB, separated by commas); anything else keeps those of the studio. */
    public static synchronized void people(String skin, String hair) {
        String list = "#[0-9a-fA-F]{6}(,#[0-9a-fA-F]{6})*";
        String wantedSkin = skin != null && skin.replace(" ", "").matches(list) ? skin.replace(" ", "") : DEFAULT_SKIN;
        String wantedHair = hair != null && hair.replace(" ", "").matches(list) ? hair.replace(" ", "") : DEFAULT_HAIR;
        if (!wantedSkin.equals(skins) || !wantedHair.equals(hairs)) {
            skins = wantedSkin;
            hairs = wantedHair;
            PICTURES.clear();
        }
    }

    public static synchronized String skin() {
        return skins;
    }

    public static synchronized String hair() {
        return hairs;
    }
    /** The clothes as the studio comes: clear colours that stand out before a dark red curtain. */
    public static final String MIXED_CLOTHES = "#2F6FB5,#2E8B6E,#D9A520,#3AA0B8,#E07A2F,#7A8A99,#4F7F3A,#C9C1A6";
    /** The setting that dresses every figure in the colour of its track. */
    public static final String TRACK_COLOUR = "track";
    public static final String DEFAULT_CLOTHES = TRACK_COLOUR;
    private static String[] cloth = MIXED_CLOTHES.split(",");
    private static String clothes = DEFAULT_CLOTHES;

    /**
     * Sets the colours the figures are dressed in: {@link #TRACK_COLOUR} for the colour of each
     * track, or #RRGGBB separated by commas, of which every track takes one. Anything else is the colour of the track.
     */
    public static synchronized void clothes(String colours) {
        String wanted = colours == null ? "" : colours.replace(" ", "");
        if (!wanted.matches("#[0-9a-fA-F]{6}(,#[0-9a-fA-F]{6})*")) {
            wanted = TRACK_COLOUR;
        }
        if (!wanted.equals(clothes)) {
            clothes = wanted;
            cloth = (wanted.equals(TRACK_COLOUR) ? MIXED_CLOTHES : wanted).split(",");
            PICTURES.clear();
        }
    }

    public static synchronized String clothes() {
        return clothes;
    }


    private static final Map<String, BufferedImage> PICTURES = new HashMap<>();

    private HouseFigures() {
    }

    /** The family of instruments these words name: the instrument, or failing that the name of the track. */
    public static String family(String words) {
        String plain = words == null ? "" : words.toLowerCase(Locale.ROOT);
        for (String[] family : FAMILIES) {
            for (int word = 1; word < family.length; word++) {
                if (plain.contains(family[word])) {
                    return family[0];
                }
            }
        }
        return "musician";
    }

    /**
     * The figure for a track, as a picture of the size the library draws its own.
     *
     * @param words what is known of the track: its instrument and its name
     * @param who   what tells this track from others, so that two violins do not look alike
     */
    public static BufferedImage picture(String words, String who) {
        return picture(words, who, null);
    }

    /**
     * The figure for a track, dressed in the colour of the track where the setting says so; another
     * colour gives another figure, in skin and hair too.
     *
     * @param colour the colour of the track on the stage, or {@code null} if it is not known
     */
    public static synchronized BufferedImage picture(String words, String who, java.awt.Color colour) {
        String family = family(words);
        String own = colour != null && clothes.equals(TRACK_COLOUR)
                ? String.format("#%06X", colour.getRGB() & 0xFFFFFF) : null;
        int look = Math.floorMod(((who == null ? "" : who) + (own == null ? "" : own)).hashCode(), 240);
        String key = family + "/" + look + (own == null ? "" : own);
        if (PICTURES.containsKey(key)) {
            return PICTURES.get(key);
        }
        BufferedImage picture;
        try {
            picture = SvgRasterizer.render(svg(family, look, own), FigureLibrary.PICTURE_WIDTH, FigureLibrary.PICTURE_HEIGHT);
        } catch (SvgRasterizer.UnreadableSvgException | RuntimeException unreadable) {
            picture = null;
        }
        PICTURES.put(key, picture);
        return picture;
    }

    /** The figure of a family as SVG, 256 wide and 512 high, standing on the bottom edge. */
    static String svg(String family, int look) {
        return svg(family, look, null);
    }

    /** @param dress the colour of the clothes as #RRGGBB, or {@code null} for one of the set colours */
    static String svg(String family, int look, String dress) {
        String[] skinTones = skins.split(",");
        String[] hairTones = hairs.split(",");
        String skin = skinTones[look % skinTones.length];
        String hair = hairTones[(look / 5) % hairTones.length];
        String cloth = dress != null ? dress : HouseFigures.cloth[(look / 3) % HouseFigures.cloth.length];
        boolean longHair = look % 2 == 1;
        StringBuilder svg = new StringBuilder("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 512'>");
        boolean sits = family.equals("drums") || family.equals("keys");
        if (family.equals("bass")) {
            // the double bass stands behind its player
            svg.append("<rect x='168' y='60' width='12' height='200' fill='#5a2d0c'/>")
                    .append("<path d='M174 230 q-46 30 -40 110 q-10 70 40 150 q50 -80 40 -150 q6 -80 -40 -110z' fill='#a8501e'/>")
                    .append("<rect x='170' y='250' width='8' height='200' fill='#2a1608'/>");
        }
        // legs and shoes
        String leg = "#1c1d2b";
        svg.append("<rect x='84' y='326' width='36' height='170' rx='12' fill='").append(leg).append("'/>")
                .append("<rect x='136' y='326' width='36' height='170' rx='12' fill='").append(leg).append("'/>")
                .append("<ellipse cx='100' cy='500' rx='26' ry='10' fill='#111'/><ellipse cx='156' cy='500' rx='26' ry='10' fill='#111'/>");
        // body, neck, head, hair
        svg.append("<path d='M78 150 Q128 118 178 150 L190 330 L66 330Z' fill='").append(cloth).append("'/>")
                .append("<rect x='116' y='112' width='24' height='30' fill='").append(skin).append("'/>");
        if (longHair) {
            svg.append("<path d='M84 70 Q80 150 100 160 L156 160 Q176 150 172 70Z' fill='").append(hair).append("'/>");
        }
        svg.append("<ellipse cx='128' cy='78' rx='38' ry='44' fill='").append(skin).append("'/>")
                .append("<path d='M90 66 Q128 10 166 66 Q128 44 90 66Z' fill='").append(hair).append("'/>")
                .append("<circle cx='114' cy='80' r='4' fill='#222'/><circle cx='142' cy='80' r='4' fill='#222'/>")
                .append("<path d='M116 98 Q128 106 140 98' stroke='#7a3b2a' stroke-width='3' fill='none'/>");
        String arm = "stroke='" + cloth + "' stroke-width='22' stroke-linecap='round' fill='none'";
        String hand = "fill='" + skin + "'";
        switch (family) {
            case "drums" -> svg.append("<path d='M84 170 L60 250' ").append(arm).append("/><path d='M172 170 L196 250' ").append(arm).append("/>")
                    .append("<path d='M60 250 L100 330' stroke='#e8d9b0' stroke-width='5'/><path d='M196 250 L160 330' stroke='#e8d9b0' stroke-width='5'/>")
                    .append("<circle cx='60' cy='250' r='11' ").append(hand).append("/><circle cx='196' cy='250' r='11' ").append(hand).append("/>")
                    .append("<rect x='70' y='350' width='116' height='90' fill='#a02a3a'/><ellipse cx='128' cy='440' rx='58' ry='16' fill='#7a1f2c'/>")
                    .append("<ellipse cx='128' cy='350' rx='58' ry='16' fill='#f2ece0'/>")
                    .append("<path d='M214 300 L214 500' stroke='#888' stroke-width='5'/><ellipse cx='214' cy='300' rx='36' ry='7' fill='#d9b84a'/>");
            case "keys" -> svg.append("<path d='M84 170 L70 270' ").append(arm).append("/><path d='M172 170 L186 270' ").append(arm).append("/>")
                    .append("<rect x='20' y='280' width='216' height='40' rx='6' fill='#15151c'/><rect x='26' y='284' width='204' height='16' fill='#f4f1e8'/>")
                    .append("<path d='M40 284v10M54 284v10M82 284v10M96 284v10M110 284v10M138 284v10M152 284v10M180 284v10M194 284v10M208 284v10' stroke='#111' stroke-width='7'/>")
                    .append("<path d='M40 320 L30 500M216 320 L226 500' stroke='#444' stroke-width='8'/>")
                    .append("<circle cx='70' cy='276' r='11' ").append(hand).append("/><circle cx='186' cy='276' r='11' ").append(hand).append("/>");
            case "synth" -> svg.append("<path d='M84 170 L74 262' ").append(arm).append("/><path d='M172 170 L182 262' ").append(arm).append("/>")
                    .append("<rect x='24' y='270' width='208' height='46' rx='8' fill='#22232e'/><rect x='34' y='294' width='188' height='14' fill='#e8e8ee'/>")
                    .append("<circle cx='50' cy='282' r='5' fill='#35ff6b'/><circle cx='70' cy='282' r='5' fill='#ff3dd4'/><circle cx='90' cy='282' r='5' fill='#2fe6ff'/><rect x='120' y='277' width='90' height='10' fill='#0b3b2c'/>")
                    .append("<path d='M128 316 L128 440 M80 500 L128 440 L176 500' stroke='#555' stroke-width='8' fill='none'/>")
                    .append("<path d='M88 62 Q128 14 168 62' stroke='#222' stroke-width='9' fill='none'/><rect x='78' y='60' width='16' height='34' rx='7' fill='#222'/><rect x='162' y='60' width='16' height='34' rx='7' fill='#222'/>")
                    .append("<circle cx='74' cy='266' r='11' ").append(hand).append("/><circle cx='182' cy='266' r='11' ").append(hand).append("/>");
            case "guitar" -> svg.append("<path d='M172 170 L200 236' ").append(arm).append("/>")
                    .append("<g transform='rotate(-28 128 270)'><rect x='122' y='110' width='14' height='150' fill='#5a2d0c'/><rect x='116' y='92' width='26' height='26' rx='4' fill='#2a1608'/>")
                    .append("<path d='M128 240 q-44 6 -44 50 q0 54 44 54 q44 0 44 -54 q0 -44 -44 -50z' fill='#c9772e'/><circle cx='128' cy='292' r='14' fill='#2a1608'/></g>")
                    .append("<path d='M84 170 L70 250 L120 286' ").append(arm).append("/>")
                    .append("<circle cx='120' cy='286' r='11' ").append(hand).append("/><circle cx='200' cy='236' r='11' ").append(hand).append("/>");
            case "bass" -> svg.append("<path d='M172 170 L176 150' ").append(arm).append("/><path d='M84 170 L96 260 L168 330' ").append(arm).append("/>")
                    .append("<circle cx='176' cy='146' r='11' ").append(hand).append("/><circle cx='168' cy='330' r='11' ").append(hand).append("/>");
            case "strings" -> svg.append("<g transform='rotate(-35 150 210)'><ellipse cx='150' cy='230' rx='30' ry='44' fill='#a8501e'/><rect x='144' y='120' width='12' height='80' fill='#5a2d0c'/></g>")
                    .append("<path d='M172 170 L206 150' ").append(arm).append("/><path d='M84 170 L64 240 L100 280' ").append(arm).append("/>")
                    .append("<path d='M70 300 L215 170' stroke='#ddd' stroke-width='5'/>")
                    .append("<circle cx='208' cy='150' r='11' ").append(hand).append("/><circle cx='100' cy='280' r='11' ").append(hand).append("/>");
            case "wind" -> svg.append("<path d='M84 170 L96 230 L124 210' ").append(arm).append("/><path d='M172 170 L164 250 L132 262' ").append(arm).append("/>")
                    .append("<path d='M128 104 L128 330' stroke='#1a1a1a' stroke-width='12' stroke-linecap='round'/><path d='M116 330 L140 330 L146 352 L110 352Z' fill='#1a1a1a'/>")
                    .append("<path d='M128 150v8M128 180v8M128 290v8' stroke='#c9c9d2' stroke-width='14'/>")
                    .append("<circle cx='126' cy='212' r='11' ").append(hand).append("/><circle cx='130' cy='262' r='11' ").append(hand).append("/>");
            case "brass" -> svg.append("<path d='M84 170 L92 150 L130 130' ").append(arm).append("/><path d='M172 170 L190 140 L160 124' ").append(arm).append("/>")
                    .append("<path d='M128 104 L214 104' stroke='#d9b84a' stroke-width='10' stroke-linecap='round'/><path d='M206 104 L240 80 L240 128Z' fill='#d9b84a'/>")
                    .append("<rect x='150' y='98' width='36' height='24' rx='8' fill='none' stroke='#d9b84a' stroke-width='6'/>")
                    .append("<circle cx='134' cy='128' r='11' ").append(hand).append("/><circle cx='160' cy='122' r='11' ").append(hand).append("/>");
            case "voice" -> svg.append("<path d='M84 170 L70 260' ").append(arm).append("/><path d='M172 170 L186 210 L150 150' ").append(arm).append("/>")
                    .append("<path d='M150 150 L138 118' stroke='#333' stroke-width='8' stroke-linecap='round'/><circle cx='136' cy='112' r='11' fill='#555'/>")
                    .append("<ellipse cx='128' cy='100' rx='8' ry='7' fill='#5a1f1f'/>")
                    .append("<circle cx='70' cy='262' r='11' ").append(hand).append("/><circle cx='152' cy='152' r='11' ").append(hand).append("/>");
            default -> svg.append("<path d='M84 170 L66 262' ").append(arm).append("/><path d='M172 170 L190 262' ").append(arm).append("/>")
                    .append("<circle cx='66' cy='264' r='11' ").append(hand).append("/><circle cx='190' cy='264' r='11' ").append(hand).append("/>");
        }
        if (sits && family.equals("keys")) {
            svg.append("<rect x='78' y='396' width='100' height='12' rx='4' fill='#2a2a33'/>");
        }
        return svg.append("</svg>").toString();
    }
}
