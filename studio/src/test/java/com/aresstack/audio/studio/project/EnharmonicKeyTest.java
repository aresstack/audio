package com.aresstack.audio.studio.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aresstack.audio.composition.Composition;
import com.aresstack.audio.composition.Key;
import com.aresstack.audio.composition.Meta;
import com.aresstack.audio.composition.TimeMap;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;

/** G flat major stays G flat major through the model file and the exports; old files stay as they were. */
public final class EnharmonicKeyTest {
    private static Composition in(Key key) {
        TimeMap time = new TimeMap(List.of(new TimeMap.Tempo(0, 90)), List.of(new TimeMap.Meter(0, 4, 4)),
                List.of(new TimeMap.KeyChange(0, key)));
        return new Composition(Meta.EMPTY, time, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    public void gFlatMajorKeepsItsFlats() throws Exception {
        Composition piece = in(Key.parse("Gb major"));
        JsonObject json = CompositionJson.write(piece);
        JsonObject key = json.getAsJsonObject("timeMap").getAsJsonArray("keys").get(0).getAsJsonObject();
        assertTrue(key.has("flats"));
        Composition read = CompositionJson.read(json);
        assertEquals(piece.timeMap(), read.timeMap());
        assertEquals(-6, read.timeMap().keyAt(0).fifths());
        assertTrue(TsvExport.lines(read).contains("key\tGb\tmajor"));
    }

    @Test
    public void keysWrittenTheUsualWayWriteNoMoreThanBefore() throws Exception {
        for (Key usual : List.of(Key.parse("F# major"), Key.parse("Eb minor"), Key.parse("D minor"), Key.C_MAJOR)) {
            JsonObject json = CompositionJson.write(in(usual));
            assertFalse(usual.name(), json.getAsJsonObject("timeMap").getAsJsonArray("keys").get(0)
                    .getAsJsonObject().has("flats"));
            assertEquals(usual, CompositionJson.read(json).timeMap().keyAt(0));
        }
    }
}
