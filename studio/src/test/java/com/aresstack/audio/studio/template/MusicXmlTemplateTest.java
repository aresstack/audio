package com.aresstack.audio.studio.template;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Key;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/**
 * MusicXML given as notes: pitches, chords, ties, voices across backup, the staves of a piano,
 * dynamics, articulation and an upbeat, read without fetching the document type over the network.
 */
public final class MusicXmlTemplateTest {
    static final String SCORE = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <!DOCTYPE score-partwise PUBLIC "-//Recordare//DTD MusicXML 4.0 Partwise//EN"
              "http://unreachable.invalid/musicxml/partwise.dtd">
            <score-partwise version="4.0">
              <work><work-title>Winter</work-title></work>
              <part-list>
                <score-part id="P1"><part-name>Violino</part-name>
                  <midi-instrument id="P1-I1"><midi-channel>1</midi-channel><midi-program>41</midi-program></midi-instrument>
                </score-part>
                <score-part id="P2"><part-name>Cembalo</part-name></score-part>
              </part-list>
              <part id="P1">
                <measure number="0" implicit="yes">
                  <attributes><divisions>2</divisions><key><fifths>-2</fifths><mode>minor</mode></key>
                    <time><beats>4</beats><beat-type>4</beat-type></time></attributes>
                  <direction><direction-type><dynamics><f/></dynamics></direction-type><sound tempo="72"/></direction>
                  <note><pitch><step>D</step><octave>5</octave></pitch><duration>2</duration><voice>1</voice></note>
                </measure>
                <measure number="1">
                  <note><pitch><step>G</step><octave>5</octave></pitch><duration>4</duration><voice>1</voice>
                    <tie type="start"/><notations><articulations><staccato/></articulations></notations></note>
                  <note><pitch><step>G</step><octave>5</octave></pitch><duration>2</duration><voice>1</voice><tie type="stop"/></note>
                  <note><rest/><duration>2</duration><voice>1</voice></note>
                </measure>
              </part>
              <part id="P2">
                <measure number="0" implicit="yes">
                  <attributes><divisions>1</divisions><staves>2</staves></attributes>
                  <note><rest/><duration>1</duration><voice>1</voice><staff>1</staff></note>
                </measure>
                <measure number="1">
                  <note><pitch><step>B</step><alter>-1</alter><octave>4</octave></pitch><duration>4</duration><voice>1</voice><staff>1</staff></note>
                  <note><chord/><pitch><step>D</step><octave>5</octave></pitch><duration>4</duration><voice>1</voice><staff>1</staff></note>
                  <backup><duration>4</duration></backup>
                  <note><grace/><pitch><step>F</step><octave>2</octave></pitch><voice>5</voice><staff>2</staff></note>
                  <note><pitch><step>G</step><octave>2</octave></pitch><duration>4</duration><voice>5</voice><staff>2</staff></note>
                </measure>
              </part>
            </score-partwise>
            """;

    @Test
    public void notesChordsTiesVoicesAndTheUpbeatAreRead() throws Exception {
        TemplateScore score = MusicXmlTemplate.read(SCORE.getBytes(StandardCharsets.UTF_8), "file");
        check(score);
    }

    @Test
    public void aCompressedFileIsReadFromTheArchive() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write(("<container><rootfiles><rootfile full-path=\"score/winter.xml\"/></rootfiles></container>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("score/winter.xml"));
            zip.write(SCORE.getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(MusicXmlTemplate.looksCompressed(bytes.toByteArray()));
        check(MusicXmlTemplate.read(bytes.toByteArray(), "file"));
    }

    private static void check(TemplateScore score) {
        assertEquals("MusicXML", score.format());
        assertEquals("Winter", score.title());
        assertEquals(72, score.bpm());
        assertEquals(4, score.numerator());
        assertEquals(4, score.denominator());
        assertEquals("two flats, minor", new Key(7, Key.Mode.MINOR), score.key());
        assertEquals(2, score.parts().size());

        ScorePart violin = score.parts().get(0);
        assertEquals("Violino", violin.name());
        assertEquals("midi-program counts from 1", 40, violin.program());
        // The upbeat of a quarter is moved to the last quarter of a full first bar.
        assertEquals(List.of(
                new ScoreNote(1440, 480, 74, 96, "", ""),
                new ScoreNote(1920, 1440, 79, 96, "", "staccato")), violin.notes());

        ScorePart cembalo = score.parts().get(1);
        assertEquals(2, cembalo.staves());
        assertEquals(-1, cembalo.program());
        assertEquals("the grace note is left out, the chord sounds together, the left hand after backup",
                List.of(new ScoreNote(1920, 1920, 43, 80, "lh", ""),
                        new ScoreNote(1920, 1920, 70, 80, "rh", ""),
                        new ScoreNote(1920, 1920, 74, 80, "rh", "")), cembalo.notes());
        assertTrue(score.remarks().toString(), score.remarks().stream().anyMatch(r -> r.contains("Vorschlagsnoten")));
    }
}
