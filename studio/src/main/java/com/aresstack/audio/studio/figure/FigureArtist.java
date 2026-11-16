package com.aresstack.audio.studio.figure;

import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelRole;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets the AI draw the figure of a musician as SVG, the way 3d4j has its people drawn
 * ({@code AssetGenerator}): the same rules of a cut-out figure standing on the bottom edge, the
 * same check that the answer can be drawn and looks like a figure, and up to {@link #ATTEMPTS}
 * tries in which the AI is told what was wrong. The answer comes as JSON, as every answer of the
 * studio's models.
 */
public final class FigureArtist {
    /** The model role of the figures, with its own choice on the settings page; without a model of its own it follows planning. */
    public static final ModelRole ROLE = ModelRoles.register(new ModelRole("figures", "Bühnenfiguren",
            "Figuren der Musiker für die Bühne zeichnen (SVG)", ModelRoles.PLANNING.key(), true));

    static final int ATTEMPTS = 3;
    /** The most tokens a figure may take; a figure is a few hundred shapes at most. */
    static final int MAX_TOKENS = 8192;

    static final String STYLE = """
            You draw figures of musicians as SVG for a music program. The program shows your drawing \
            as a small flat cut-out figure standing on a dark stage, at the place of the instrument.

            Answer with JSON only, no Markdown: {"svg": "<svg ...>...</svg>"}.
            Write every attribute value of the SVG in single quotes, so that the JSON needs no escaping.

            Rules for the SVG:
            - Root element: <svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 256 512'>.
            - The background must stay transparent: no rectangle behind the figure.
            - Draw one person, seen from the front, with the instrument in their hands and playing it. \
            The person stands on the bottom edge (y = 512), is centered horizontally and fills most \
            of the height. A drummer or a pianist may sit; then the drums or the piano stand in front.
            - The instrument must be easy to recognise even when the figure is small: its typical \
            shape, size and colour.
            - Cartoon style: flat, friendly, bright colours that stand out on a dark background, \
            thick dark outlines (stroke='#2A2233', stroke-width 6 to 8, round joins), a few lighter highlights.
            - Use only basic shapes and paths with solid fills. No text, no images, no filters, \
            no gradients, no scripts, no external references.""";

    /** Looks that make figures of the same instrument different people; the number of a figure picks one. */
    static final List<String> LOOKS = List.of(
            "a young woman with long dark hair in a red dress",
            "an elderly man with a grey beard and a dark suit",
            "a young man with curly hair in a blue shirt",
            "a woman with short blond hair in a green jacket",
            "a man with a bald head and round glasses in a yellow sweater",
            "a teenager with a ponytail in a purple hoodie",
            "a woman with braided hair in an orange blouse",
            "a man with a moustache and a hat in a waistcoat");

    private final ChatModel model;

    public FigureArtist(ChatModel model) {
        this.model = model;
    }

    public String modelName() {
        return model.name();
    }

    /**
     * Draws a musician.
     *
     * @param instrument what the track plays, as the piece names it
     * @param role       the role of the track (melody, harmony, bass, drums, texture)
     * @param variation  which of the {@link #LOOKS} the person gets
     * @param answers    the folder where the answers of the AI are kept as a record
     * @return the SVG, checked: it can be drawn and looks like a figure
     */
    public String draw(String instrument, String role, int variation, Path answers) throws IOException {
        String problem = null;
        Files.createDirectories(answers);
        String stem = FigureLibrary.kind(instrument) + "-" + System.currentTimeMillis();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Zeichnen abgebrochen.");
            }
            String prompt = prompt(instrument, role, variation)
                    + (problem == null ? "" : "\n\nYour previous answer was unusable: " + problem
                    + ". Follow the rules exactly and answer with the corrected JSON only.");
            JsonObject answer;
            try {
                // A new file for every try: a kept answer must never stand in for a new drawing.
                answer = model.generate(STYLE, prompt, MAX_TOKENS, answers.resolve(stem + "-" + attempt + ".json"));
            } catch (com.aresstack.audio.studio.ai.UnreadableAnswerException unreadable) {
                problem = "it was not valid JSON with an \"svg\" field";
                continue;
            }
            String svg = SvgRasterizer.extract(svgIn(answer));
            if (svg.isEmpty()) {
                problem = "it contained no <svg> element";
                continue;
            }
            try {
                problem = figureProblem(SvgRasterizer.render(svg, FigureLibrary.PICTURE_WIDTH, FigureLibrary.PICTURE_HEIGHT));
            } catch (SvgRasterizer.UnreadableSvgException unreadable) {
                problem = unreadable.getMessage();
            }
            if (problem == null) {
                return svg;
            }
        }
        throw new IOException("Die KI hat keine brauchbare Figur gezeichnet (" + problem + ").");
    }

    static String prompt(String instrument, String role, int variation) {
        String look = LOOKS.get(Math.floorMod(variation, LOOKS.size()));
        return "Draw: a musician playing \"" + instrument + "\" (role in the piece: " + role + "), " + look + ". "
                + "If the name is a group or a sound rather than one instrument (an ensemble, a pad, a synth), "
                + "draw one musician who stands for it with a fitting instrument (for strings a violin, "
                + "for a synth or pad a keyboard).";
    }

    /** The SVG in an answer: its field {@code svg}, or any text in it that holds one. */
    static String svgIn(JsonObject answer) {
        if (answer == null) {
            return "";
        }
        JsonElement svg = answer.get("svg");
        if (svg != null && svg.isJsonPrimitive()) {
            return svg.getAsString();
        }
        for (Map.Entry<String, JsonElement> entry : answer.entrySet()) {
            if (entry.getValue().isJsonPrimitive()
                    && entry.getValue().getAsString().toLowerCase(Locale.ROOT).contains("<svg")) {
                return entry.getValue().getAsString();
            }
        }
        return "";
    }

    /** A figure stands free: something is drawn, and the background stayed transparent (as in 3d4j). */
    static String figureProblem(BufferedImage image) {
        double coverage = SvgRasterizer.coverage(image);
        if (coverage < 0.03) {
            return "the drawing is empty or tiny";
        }
        return coverage > 0.93 ? "the background is not transparent" : null;
    }
}
