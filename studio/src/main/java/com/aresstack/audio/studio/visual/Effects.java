package com.aresstack.audio.studio.visual;

import java.util.List;

/** The effects the visualizer offers, in the order of their keys. Each call makes fresh instances for one view. */
public final class Effects {
    private Effects() {
    }

    public static List<Effect> all() {
        return List.of(new SpectrumEffect(), new ScopeEffect(), new MeterEffect(), new TunnelEffect(),
                new ParticleEffect(), new AlchemyEffect(), new StageLightEffect());
    }

    /** The effect with this key from the list, else the first one. */
    public static Effect find(List<Effect> effects, String key) {
        for (Effect effect : effects) {
            if (effect.key().equals(key)) {
                return effect;
            }
        }
        return effects.get(0);
    }
}
