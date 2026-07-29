package io.atlasarc.parity.fixtures.modules.shared.left;

import io.atlasarc.parity.fixtures.modules.shared.right.BillingRight;

public final class BillingLeft {
    public int call(BillingRight right) {
        return right.touch();
    }

    public int touch() {
        return 3;
    }
}
