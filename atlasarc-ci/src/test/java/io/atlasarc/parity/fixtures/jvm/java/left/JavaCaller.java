package io.atlasarc.parity.fixtures.jvm.java.left;

import io.atlasarc.parity.fixtures.jvm.java.right.OverloadedTarget;

public final class JavaCaller {
    public String callBoth() {
        OverloadedTarget number = new OverloadedTarget(7);
        OverloadedTarget text = new OverloadedTarget("seven");
        number.value = 8;
        return number.load(8) + text.load("eight") + number.value + number.callBack(this);
    }

    public int ping() {
        return 1;
    }
}
