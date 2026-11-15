/**
 * Parameters that move over time (architecture review, work package D3): level, place and room
 * share of a channel. The mix document keeps its points in ticks of the piece; whoever builds the
 * graph turns ticks into time with the tempo of the piece and hands the audio core a
 * {@link com.aresstack.audio.core.automation.AutomationCurve} in frames, so that the core knows
 * neither ticks nor bars and a change of tempo keeps the movement at its bar.
 */
package com.aresstack.audio.core.automation;
