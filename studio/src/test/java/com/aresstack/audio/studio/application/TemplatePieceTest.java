package com.aresstack.audio.studio.application;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.music.arrangement.ArrangementFile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A piece made from notes the user gave: the arrangement holds the notes of the template with its
 * tempo, meter, key and length, the plan says which template it follows, and the template is kept
 * with the piece (and with a title of an album, from where the piece gets it).
 */
public final class TemplatePieceTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final String WINTER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <score-partwise version="4.0">
              <part-list><score-part id="P1"><part-name>Violino</part-name></score-part>
                <score-part id="P2"><part-name>Pianoforte</part-name></score-part></part-list>
              <part id="P1">
                <measure number="1">
                  <attributes><divisions>1</divisions><key><fifths>-3</fifths><mode>minor</mode></key>
                    <time><beats>3</beats><beat-type>4</beat-type></time></attributes>
                  <sound tempo="66"/>
                  <note><pitch><step>G</step><octave>5</octave></pitch><duration>1</duration>
                    <notations><articulations><accent/></articulations></notations></note>
                  <note><pitch><step>A</step><alter>-1</alter><octave>5</octave></pitch><duration>2</duration></note>
                </measure>
                <measure number="2">
                  <note><pitch><step>G</step><octave>5</octave></pitch><duration>3</duration></note>
                </measure>
              </part>
              <part id="P2">
                <measure number="1">
                  <attributes><divisions>1</divisions><staves>2</staves></attributes>
                  <note><pitch><step>C</step><octave>4</octave></pitch><duration>3</duration><staff>1</staff></note>
                  <backup><duration>3</duration></backup>
                  <note><pitch><step>C</step><octave>3</octave></pitch><duration>3</duration><staff>2</staff></note>
                </measure>
              </part>
            </score-partwise>
            """;

    @Test
    public void theTemplateBecomesThePieceAsWritten() throws Exception {
        Path given = temporary.newFile("winter.musicxml").toPath();
        Files.write(given, WINTER.getBytes(StandardCharsets.UTF_8));
        Path piece = temporary.newFolder("piece").toPath();
        Path kept = ScoreTemplates.put(piece, given);
        assertEquals(kept, ScoreTemplates.of(piece).orElseThrow());

        List<String> told = new ArrayList<String>();
        TemplatePiece.Made made = new TemplatePiece(null, 4096, 40).read(kept, "", piece, told::add);
        assertEquals(CompositionSpec.of(2, 66, com.aresstack.audio.composition.Key.parse("C minor"), 3, 4), made.spec());
        assertEquals(List.of("violino", "pianoforte"), made.tracks().tracks().stream().map(MusicTrack::id).toList());
        Path arrangement = TemplatePiece.write(made, made.tracks(), piece, kept);

        ArrangementFile file = ArrangementFile.read(arrangement);
        assertEquals(List.of(), file.problems());
        assertEquals(66, file.tempoOrDefault());
        assertEquals(3, file.meter().orElseThrow().numerator());
        assertEquals(List.of("violino", "pianoforte"), file.trackIds());
        List<ArrangementFile.Note> notes = file.notes();
        assertEquals(5, notes.size());
        assertEquals(79, notes.get(0).pitch());
        assertEquals("accent", notes.get(0).articulation());
        assertEquals(1440, notes.get(2).tick());
        assertEquals(1440, notes.get(2).length());
        assertTrue("the piano is written on a grand staff with its hands",
                Files.readAllLines(arrangement).contains("staff\tpianoforte\tgrand"));
        assertEquals("lh", notes.get(3).voice().isEmpty() ? notes.get(4).voice() : notes.get(3).voice());

        JsonObject plan = JsonParser.parseString(Files.readString(piece.resolve("composition-plan.json")))
                .getAsJsonObject();
        assertEquals("winter.musicxml", plan.get("template").getAsString());
        assertEquals(2, plan.get("bars").getAsInt());
        assertEquals(made.spec(), GemmaMusicComposer.specOf(piece));
        assertTrue(told.toString(), told.stream().anyMatch(line -> line.contains("2 Stimmen, 5 Noten, 2 Takte")));
        assertTrue(Files.isRegularFile(piece.resolve(TemplatePiece.CORRECTIONS_FILE)));
    }

    @Test
    public void aTitleHandsItsTemplateToItsPieceAndATemplateCanBeTakenBack() throws Exception {
        Path given = temporary.newFile("autunno.mid").toPath();
        Files.write(given, new byte[]{'M', 'T', 'h', 'd', 1});
        Path title = temporary.newFolder("album", "titles", "t1").toPath();
        Path piece = temporary.newFolder("piece2").toPath();
        assertFalse(ScoreTemplates.follow(title, piece));

        ScoreTemplates.put(title, given);
        assertTrue(ScoreTemplates.follow(title, piece));
        assertEquals("autunno.mid", ScoreTemplates.of(piece).orElseThrow().getFileName().toString());

        Path other = temporary.newFile("inverno.mid").toPath();
        Files.write(other, new byte[]{'M', 'T', 'h', 'd', 2});
        ScoreTemplates.put(title, other);
        ScoreTemplates.follow(title, piece);
        assertEquals("a new template replaces the one before", "inverno.mid",
                ScoreTemplates.of(piece).orElseThrow().getFileName().toString());
        try (var files = Files.list(piece.resolve(ScoreTemplates.FOLDER))) {
            assertEquals(1, files.count());
        }

        ScoreTemplates.remove(piece);
        assertFalse(ScoreTemplates.of(piece).isPresent());
        assertFalse(Files.exists(piece.resolve(ScoreTemplates.FOLDER)));
    }
}
