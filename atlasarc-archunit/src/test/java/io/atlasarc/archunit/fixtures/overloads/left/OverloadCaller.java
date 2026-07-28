package io.atlasarc.archunit.fixtures.overloads.left;

import io.atlasarc.archunit.fixtures.overloads.right.OverloadedTarget;

public final class OverloadCaller {
    public String callBoth() {
        OverloadedTarget number = new OverloadedTarget(7);
        OverloadedTarget text = new OverloadedTarget("seven");
        number.value = 8;
        return number.load(8) + text.load("eight") + number.value;
    }
}
