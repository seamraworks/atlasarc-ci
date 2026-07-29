package io.atlasarc.parity.fixtures.jvm.java.right;

import io.atlasarc.parity.fixtures.jvm.java.left.JavaCaller;

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

    public int callBack(JavaCaller caller) {
        return caller.ping();
    }
}
