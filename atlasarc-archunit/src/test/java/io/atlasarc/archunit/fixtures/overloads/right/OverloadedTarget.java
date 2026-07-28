package io.atlasarc.archunit.fixtures.overloads.right;

public final class OverloadedTarget {
    public int value;

    public OverloadedTarget(int value) {
        this.value = value;
    }

    public OverloadedTarget(String value) {
        this.value = value.length();
    }

    public String load(int input) {
        return Integer.toString(input);
    }

    public String load(String input) {
        return input;
    }
}
