package com.javahost.client.compat.input;

/** Minecraft 1.21.9's MouseButtonEvent, for older versions (they pass x, y and the button separately). */
public record MouseButtonEvent(double x, double y, int button) {
}
