package com.aresstack.audio.studio.album;

import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.ai.UnreadableAnswerException;
import com.aresstack.audio.studio.figure.FigureArtist;
import com.aresstack.audio.studio.figure.SvgRasterizer;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lets the AI draw the cover of an album as SVG, the way it draws the figures of the stage
 * ({@link FigureArtist}): the answer comes as JSON, is checked (it can be drawn and fills the
 * square) and is asked for again up to {@link #ATTEMPTS} times, with what was wrong. A drawing of
 * shapes is no painting, but it is a cover of the album's own, and better than none.
 */
public final class CoverArtist {
    /** The model role of the covers; without a model of its own it follows the album's planning. */
    public static final ModelRole ROLE = ModelRoles.register(new ModelRole("cover", "Albumcover",
            "Cover eines Albums zeichnen (SVG)", AlbumRoles.ALBUM.key(), false));

    static final int ATTEMPTS = 3;
    /** The most tokens a cover may take. */
    static final int MAX_TOKENS = 8192;

    static final String STYLE = """
            You design the cover of a music album as SVG. A music program shows it as the picture of \
            the album and exports it as cover.png beside the audio files.

            Answer with JSON only, no Markdown: {"svg": "<svg ...>...</svg>"}.
            Write every attribute value of the SVG in single quotes, so that the JSON needs no escaping.

            Rules for the SVG:
            - Root element: <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 1000 1000'>.
            - Begin with a background that fills the whole square (a rectangle from 0,0 to 1000,1000); \
            a gradient is welcome.
            - One clear motif that fits the title, the description and the style of the album, \
            composed like a poster: large simple shapes, few harmonious colours, good contrast. \
            It must still read when the cover is shown small.
            - Write the title of the album on it, exactly as given, large and easy to read, with \
            <text font-family='sans-serif' text-anchor='middle'>; break a long title into several \
            lines that all stay inside the square. No other text.
            - Use basic shapes, paths, text and linear or radial gradients only. No images, \
            no filters, no scripts, no external references.""";

    /** A cover the AI drew: the drawing and the picture made of it. */
    public record Drawn(String svg, BufferedImage image) {
    }

    private final ChatModel model;

    public CoverArtist(ChatModel model) {
        this.model = model;
    }

    /**
     * Draws the cover of an album.
     *
     * @param answers the folder where the answers of the AI are kept as a record
     * @return the cover, checked: it can be drawn and fills the square
     */
    public Drawn draw(Album album, Path answers) throws IOException {
        String problem = null;
        Files.createDirectories(answers);
        String stem = "cover-" + System.currentTimeMillis();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Zeichnen abgebrochen.");
            }
            String prompt = prompt(album) + (problem == null ? "" : "\n\nYour previous answer was unusable: " + problem
                    + ". Follow the rules exactly and answer with the corrected JSON only.");
            JsonObject answer;
            try {
                // A new file for every try: a kept answer must never stand in for a new drawing.
                answer = model.generate(STYLE, prompt, MAX_TOKENS, answers.resolve(stem + "-" + attempt + ".json"));
            } catch (UnreadableAnswerException unreadable) {
                problem = "it was not valid JSON with an \"svg\" field";
                continue;
            }
            String svg = SvgRasterizer.extract(FigureArtist.svgIn(answer));
            if (svg.isEmpty()) {
                problem = "it contained no <svg> element";
                continue;
            }
            try {
                BufferedImage image = SvgRasterizer.render(svg, AlbumCover.SIZE, AlbumCover.SIZE);
                problem = coverProblem(image);
                if (problem == null) {
                    return new Drawn(svg, image);
                }
            } catch (SvgRasterizer.UnreadableSvgException unreadable) {
                problem = unreadable.getMessage();
            }
        }
        throw new IOException("Die KI hat kein brauchbares Cover gezeichnet (" + problem + ").");
    }

    /** What the AI is told about the album: its title, what it is about, its style and its titles. */
    static String prompt(Album album) {
        StringBuilder prompt = new StringBuilder("Design the cover of this album.\nTitle: \"")
                .append(album.title()).append('"');
        if (!album.concept().description().isBlank()) {
            prompt.append("\nWhat it is about: ").append(album.concept().description().strip());
        }
        if (!album.concept().style().isBlank()) {
            prompt.append("\nMusical style: ").append(album.concept().style().strip());
        }
        if (!album.tracks().isEmpty()) {
            prompt.append("\nIts titles (for the mood only, do not write them on the cover):");
            for (AlbumTrack track : album.tracks()) {
                prompt.append("\n- ").append(track.title());
                if (track.mood() != null && !track.mood().isBlank()) {
                    prompt.append(" (").append(track.mood().strip()).append(')');
                }
            }
        }
        return prompt.toString();
    }

    /** A cover fills its square: a drawing on a see-through ground is none. */
    static String coverProblem(BufferedImage image) {
        return SvgRasterizer.coverage(image) < 0.97
                ? "the cover does not fill the whole square: begin with a background rectangle from 0,0 to 1000,1000"
                : null;
    }
}
