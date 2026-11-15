package com.aresstack.audio.studio.application;

import com.aresstack.audio.composition.CompositionValidator.Violation;
import java.util.List;

/** A patch the edit core refused; the piece is unchanged. */
public final class EditRefusedException extends Exception {
    private final transient List<Violation> violations;

    public EditRefusedException(List<Violation> violations) {
        super("Die Änderung wurde abgelehnt: " + violations);
        this.violations = List.copyOf(violations);
    }

    /** Why the patch was refused. */
    public List<Violation> violations() {
        return violations;
    }
}
