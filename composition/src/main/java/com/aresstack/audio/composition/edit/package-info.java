/**
 * The edit core, work package F3 (architecture review 2.3, E5): every change of a piece, by hand
 * or by an AI, is a {@link com.aresstack.audio.composition.edit.Patch} of operations on ids,
 * applied by {@link com.aresstack.audio.composition.edit.CompositionEditor} within an
 * {@link com.aresstack.audio.composition.edit.EditScope}. The editor refuses operations outside the
 * scope and proves with {@link com.aresstack.audio.composition.edit.ScopeProof} that nothing outside
 * changed; the only change outside it may make is an approved, deterministic
 * {@link com.aresstack.audio.composition.edit.TimeShift}.
 *
 * <p>A {@link com.aresstack.audio.composition.edit.Proposal} is an applied patch that is not taken
 * over yet; {@link com.aresstack.audio.composition.edit.EditHistory} keeps undo and redo of a
 * session; a {@link com.aresstack.audio.composition.edit.Revision} is a lasting full copy;
 * {@link com.aresstack.audio.composition.edit.ChangeSet} says which parts must be rendered again.
 *
 * <p>Like the rest of the model, the package knows no files, windows, sound or AI generator.
 */
package com.aresstack.audio.composition.edit;
