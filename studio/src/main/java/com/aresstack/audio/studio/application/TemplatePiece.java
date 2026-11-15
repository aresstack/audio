package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.Key;
import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.aresstack.audio.studio.ai.ChatModel;
import com.aresstack.audio.studio.ai.ModelLineup;
import com.aresstack.audio.studio.ai.ModelRoles;
import com.aresstack.audio.studio.progress.Progress;
import com.aresstack.audio.studio.progress.SketchProgress;
import com.aresstack.audio.studio.template.ScoreNote;
import com.aresstack.audio.studio.template.ScorePart;
import com.aresstack.audio.studio.template.TemplateCleanup;
import com.aresstack.audio.studio.template.TemplateReader;
import com.aresstack.audio.studio.template.TemplateReview;
import com.aresstack.audio.studio.template.TemplateScore;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A piece made from notes the user gave instead of composed freely: the first step is bound to
 * the template. The template is read (MIDI and MusicXML by the studio itself, other notations by
 * the AI), what is plainly wrong is set right, and the AI only decides who plays each part and
 * looks at the places that seem to be slips of the pen. Tempo, meter, key and length are the
 * template's; what it does not say, the studio assumes. The notes are shown in the score as soon
 * as they are read, and again once they are checked.
 */
public final class TemplatePiece {
    /**
     * What came of a template, before it is written into the folder.
     *
     * @param setups who plays each part of {@code score}, in its order
     */
    public record Made(TemplateScore score, List<TemplateReview.Setup> setups, TrackArrangement tracks,
                       CompositionSpec spec, String title, String mood, List<String> remarks) {
    }

    /** What was set right in the template, in words, kept in the folder of the piece. */
    public static final String CORRECTIONS_FILE = "template-corrections.txt";

    private final ModelLineup models;
    private final int maxTokens;
    private final int linesPerRequest;

    /**
     * @param models          the models; {@code null} reads only MIDI and MusicXML and lets the
     *                        studio decide who plays
     * @param linesPerRequest lines of a text template the AI writes over in one request
     */
    public TemplatePiece(ModelLineup models, int maxTokens, int linesPerRequest) {
        this.models = models;
        this.maxTokens = maxTokens;
        this.linesPerRequest = linesPerRequest;
    }

    /** Reads, sets right and looks over the template; nothing is written into the piece yet. */
    public Made read(Path template, String description, Path folder, Progress progress) throws IOException {
        SketchProgress sketch = progress instanceof SketchProgress shown ? shown : null;
        String name = template.getFileName().toString();
        progress.update("Lese die Notenvorlage „" + name + "“ ...");
        if (sketch != null) {
            sketch.preparing("Notenvorlage lesen", 0);
        }
        ChatModel transcriber = models == null ? null : models.model(ModelRoles.COMPOSITION);
        TemplateScore read = TemplateReader.read(template, transcriber, maxTokens, linesPerRequest, folder,
                (portion, characters) -> {
                    if (sketch != null) {
                        sketch.preparing("Notenvorlage übertragen, " + portion, characters);
                    }
                });
        TemplateScore cleaned = TemplateCleanup.clean(read);
        if (cleaned.parts().isEmpty()) {
            throw new IOException("In der Notenvorlage „" + name + "“ steht keine spielbare Note.");
        }
        progress.update("Notenvorlage gelesen (" + cleaned.format() + "): " + cleaned.parts().size() + " Stimmen, "
                + cleaned.noteCount() + " Noten, " + cleaned.bars() + " Takte.");
        // What was read is shown at once, with the instruments the template names, before the AI looks at it.
        Made first = made(TemplateReview.asWritten(cleaned), cleaned, List.of());
        show(sketch, first);
        if (sketch != null) {
            sketch.preparing("Besetzung und auffällige Stellen prüfen", 0);
        }
        TemplateReview.Reviewed reviewed = TemplateReview.review(cleaned, description,
                models == null ? null : models.model(ModelRoles.PLANNING), maxTokens, folder,
                characters -> {
                    if (sketch != null) {
                        sketch.preparing("Besetzung und auffällige Stellen prüfen", characters);
                    }
                });
        List<String> remarks = new ArrayList<String>(cleaned.remarks());
        remarks.addAll(reviewed.remarks());
        Made made = made(reviewed, cleaned, remarks);
        show(sketch, made);
        for (String remark : made.remarks()) {
            progress.update("Vorlage: " + remark);
        }
        return made;
    }

    private static Made made(TemplateReview.Reviewed reviewed, TemplateScore read, List<String> remarks) {
        TemplateScore score = reviewed.score();
        List<MusicTrack> tracks = new ArrayList<MusicTrack>();
        for (TemplateReview.Setup setup : reviewed.setups()) {
            tracks.add(new MusicTrack(setup.id(), setup.role(), setup.instrument(), setup.program(), "builtin"));
        }
        int bpm = read.bpm() > 0 ? read.bpm() : ArrangementFile.DEFAULT_TEMPO;
        bpm = Math.max(CompositionSpec.MIN_BPM, Math.min(CompositionSpec.MAX_BPM, bpm));
        boolean meterKnown = read.numerator() >= 1 && read.numerator() <= 32 && read.denominator() >= 1
                && read.denominator() <= 16 && Integer.bitCount(read.denominator()) == 1;
        int numerator = meterKnown ? read.numerator() : 4;
        int denominator = meterKnown ? read.denominator() : 4;
        Key key = reviewed.key() == null ? Key.C_MAJOR : reviewed.key();
        List<String> all = new ArrayList<String>(remarks);
        int bars = score.bars();
        if (bars > CompositionSpec.MAX_BARS) {
            all.add("Die Vorlage hat " + bars + " Takte; ein Stück hat höchstens " + CompositionSpec.MAX_BARS
                    + ", der Rest ist weggelassen.");
            bars = CompositionSpec.MAX_BARS;
        }
        if (!meterKnown) {
            all.add("Die Vorlage nennt keine Taktart; angenommen ist 4/4.");
        }
        if (read.bpm() <= 0) {
            all.add("Die Vorlage nennt kein Tempo; angenommen sind " + bpm + " BPM.");
        }
        CompositionSpec spec = CompositionSpec.of(bars, bpm, key, numerator, denominator);
        return new Made(score, reviewed.setups(), new TrackArrangement(tracks), spec, reviewed.title(),
                reviewed.mood(), all);
    }

    private static void show(SketchProgress sketch, Made made) {
        if (sketch == null) {
            return;
        }
        List<String[]> chosen = new ArrayList<String[]>();
        for (MusicTrack track : made.tracks().tracks()) {
            chosen.add(new String[]{track.id(), track.role(), track.instrument(), Integer.toString(track.program())});
        }
        sketch.instruments(chosen);
        sketch.sketch(lines(made, made.tracks()));
    }

    /** The lines of {@code arrangement.tsv} of a piece made from a template. */
    public static List<String> lines(Made made, TrackArrangement tracks) {
        CompositionSpec spec = made.spec();
        long last = spec.ticks();
        long end = 0;
        List<String> body = new ArrayList<String>();
        for (int p = 0; p < made.score().parts().size() && p < tracks.tracks().size(); p++) {
            ScorePart part = made.score().parts().get(p);
            MusicTrack track = tracks.tracks().get(p);
            TemplateReview.Setup setup = made.setups().get(p);
            body.add("track\t" + track.id() + "\t" + track.program() + "\t" + tracks.channelFor(track.id()) + "\t"
                    + track.role());
            if (setup.grand()) {
                body.add("staff\t" + track.id() + "\tgrand");
            }
            for (ScoreNote note : part.notes()) {
                if (note.tick() >= last) {
                    continue;
                }
                long length = Math.min(note.length(), last - note.tick());
                String voice = setup.grand() ? note.voice() : "";
                StringBuilder line = new StringBuilder("note\t").append(track.id()).append('\t').append(note.tick())
                        .append('\t').append(length).append('\t').append(note.pitch()).append('\t')
                        .append(note.velocity());
                if (!voice.isEmpty() || !note.articulation().isEmpty()) {
                    line.append('\t').append(voice);
                }
                if (!note.articulation().isEmpty()) {
                    line.append('\t').append(note.articulation());
                }
                body.add(line.toString());
                end = Math.max(end, note.tick() + length);
            }
        }
        long bar = spec.barTicks();
        int covering = (int) Math.max(1, (end + bar - 1) / bar);
        List<String> lines = new ArrayList<String>(spec.headerLines(covering));
        lines.addAll(body);
        return lines;
    }

    /**
     * Writes the piece into its folder: the arrangement, the plan (title, mood, tempo, key,
     * meter, bars and which template it follows), the spec and what was set right. The tracks
     * and the stage the caller writes, as for a composed piece.
     *
     * @param tracks the tracks of {@code made} with the sound banks chosen for them
     */
    public static Path write(Made made, TrackArrangement tracks, Path folder, Path template) throws IOException {
        Files.createDirectories(folder);
        CompositionSpec spec = made.spec();
        JsonObject plan = new JsonObject();
        plan.addProperty("title", made.title().isEmpty() ? template.getFileName().toString() : made.title());
        if (!made.mood().isEmpty()) {
            plan.addProperty("mood", made.mood());
        }
        plan.addProperty("tempoBpm", spec.bpm());
        plan.addProperty("key", spec.key().name());
        plan.addProperty("timeSignature", spec.meter());
        plan.addProperty("bars", spec.bars());
        plan.addProperty("snapToHarmony", false);
        plan.addProperty("template", template.getFileName().toString());
        plan.addProperty("templateFormat", made.score().format());
        GsonBuilder gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping();
        Files.write(folder.resolve("composition-plan.json"),
                gson.create().toJson(plan).getBytes(StandardCharsets.UTF_8));
        JsonObject kept = new JsonObject();
        kept.addProperty("tempoBpm", spec.bpm());
        kept.addProperty("key", spec.key().name());
        kept.addProperty("timeSignature", spec.meter());
        kept.addProperty("bars", spec.bars());
        kept.addProperty("snapToHarmony", false);
        Files.write(folder.resolve(GemmaMusicComposer.SPEC_FILE), gson.create().toJson(kept)
                .getBytes(StandardCharsets.UTF_8));
        List<String> corrections = new ArrayList<String>();
        corrections.add("# Was an der Notenvorlage " + template.getFileName() + " berichtigt wurde");
        corrections.addAll(made.remarks());
        Files.write(folder.resolve(CORRECTIONS_FILE), corrections, StandardCharsets.UTF_8);
        Path arrangement = folder.resolve("arrangement.tsv");
        Files.write(arrangement, lines(made, tracks), StandardCharsets.UTF_8);
        return arrangement;
    }
}
