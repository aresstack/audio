package com.aresstack.audio.instruments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Provide researched sound-library entries and their provenance for the local studio. */
public final class InstrumentCatalog {
    private InstrumentCatalog() {
    }

    public static List<InstrumentLibrary> standard() {
        List<InstrumentLibrary> libraries = new ArrayList<InstrumentLibrary>();
        libraries.add(new InstrumentLibrary("generaluser", "GeneralUser GS 2.0.3", "General MIDI",
                InstrumentLibrary.Format.SF2, "GeneralUser GS License v2.0",
                "https://github.com/mrbumpy409/GeneralUser-GS",
                "Complete GM bank; already tested with FluidSynth."));
        libraries.add(new InstrumentLibrary("musescore-general", "MuseScore General", "General MIDI",
                InstrumentLibrary.Format.SF3, "MIT (MuseScore General)",
                "https://musescore.org/en/handbook/3/soundfonts-and-sfz-files",
                "Use MuseScore_General.sf3; SF2 variant can be registered as well."));
        libraries.add(new InstrumentLibrary("vsco2", "VSCO 2 Community Edition", "Orchestra",
                InstrumentLibrary.Format.SFZ, "CC0 (Community Edition)",
                "https://versilian-studios.com/vsco-community/",
                "Select an individual SFZ instrument with its accompanying samples (approx. 3 GB library)."));
        libraries.add(new InstrumentLibrary("vpo", "Virtual Playing Orchestra", "Orchestra",
                InstrumentLibrary.Format.SFZ, "Mixed origins: verify individual sample licenses",
                "https://virtualplaying.com/virtual-playing-orchestra/",
                "Select a single instrument and articulation; derived from multiple source libraries."));
        libraries.add(new InstrumentLibrary("salamander", "Salamander Grand Piano", "Piano",
                InstrumentLibrary.Format.SFZ, "CC BY 3.0 (Alexander Holm)",
                "https://sfzinstruments.github.io/pianos/salamander/",
                "ARIA-extension SFZ: check compatibility with the chosen sampler."));
        libraries.add(new InstrumentLibrary("spanish-guitar", "Spanish Classical Guitar", "Guitar",
                InstrumentLibrary.Format.SFZ, "CC0 (FreePats)",
                "https://github.com/freepats/spanish-classical-guitar",
                "SFZ definition and neighboring sample files must remain together."));
        libraries.add(new InstrumentLibrary("fsbs-clean", "FSBS Electric Guitar Clean", "Guitar",
                InstrumentLibrary.Format.SF2, "CC0 (FreePats)",
                "https://freepats.zenvoid.org/ElectricGuitar/clean-electric-guitar.html",
                "Choose an SF2 release, including the compact bank; SFZ variants are also offered upstream."));
        libraries.add(new InstrumentLibrary("freepats", "FreePats additional instruments", "Various",
                InstrumentLibrary.Format.SFZ, "Varies by package; review the individual license",
                "https://freepats.zenvoid.org/",
                "Register any locally installed SFZ; percussion, winds, bagpipes and other instruments."));
        libraries.add(new InstrumentLibrary("sfzinstruments", "SFZ Instruments / GitHub", "Choirs, winds, exotic",
                InstrumentLibrary.Format.SFZ, "Varies by repository; review the individual license",
                "https://sfzinstruments.github.io/",
                "Register any legally acquired and supported SFZ instrument; no arbitrary downloads."));
        return Collections.unmodifiableList(libraries);
    }
}
