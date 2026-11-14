package com.aresstack.audio.instruments.download;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** List only independently verified, directly downloadable and small soundbanks. */
public final class InstrumentDownloadCatalog {
    private static final Map<String, InstrumentDownload> SOURCES;

    static {
        Map<String, InstrumentDownload> available = new LinkedHashMap<String, InstrumentDownload>();
        available.put("generaluser", new InstrumentDownload("generaluser",
                "https://raw.githubusercontent.com/mrbumpy409/GeneralUser-GS/main/GeneralUser-GS.sf2",
                "GeneralUser-GS.sf2", InstrumentDownload.Packaging.RAW_SF2,
                45_000_000L, 32_319_396L,
                "9575028c7a1f589f5770fccc8cff2734566af40cd26ed836944e9a5152688cfe"));
        available.put("musescore-general", new InstrumentDownload("musescore-general",
                "https://ftp.osuosl.org/pub/musescore/soundfont/MuseScore_General/MuseScore_General.sf3",
                "MuseScore_General.sf3", InstrumentDownload.Packaging.RAW_SF3,
                55_000_000L, 39_900_972L, ""));
        available.put("spanish-guitar", new InstrumentDownload("spanish-guitar",
                "https://freepats.zenvoid.org/Guitar/SpanishClassicalGuitar/SpanishClassicalGuitar-SF2-20190618.7z",
                "SpanishClassicalGuitar-SF2-20190618.7z",
                InstrumentDownload.Packaging.SEVEN_Z_SF2,
                18_000_000L, 10_000_000L, ""));
        available.put("fsbs-clean", new InstrumentDownload("fsbs-clean",
                "https://github.com/freepats/electric-guitar-FSBS-clean/releases/download/2026-08-07/EGuitarFSBS-clean-bridge-small-SF2-20260807.7z",
                "EGuitarFSBS-clean-bridge-small-SF2-20260807.7z",
                InstrumentDownload.Packaging.SEVEN_Z_SF2,
                15_000_000L, 6_700_000L, ""));
        SOURCES = Collections.unmodifiableMap(available);
    }

    private InstrumentDownloadCatalog() {
    }

    public static InstrumentDownload forLibrary(String libraryId) {
        return SOURCES.get(libraryId);
    }

    public static List<InstrumentDownload> all() {
        return Collections.unmodifiableList(new ArrayList<InstrumentDownload>(SOURCES.values()));
    }

    public static long estimatedTotalBytes() {
        long total = 0;
        for (InstrumentDownload item : SOURCES.values()) {
            total += item.estimatedBytes();
        }
        return total;
    }
}
