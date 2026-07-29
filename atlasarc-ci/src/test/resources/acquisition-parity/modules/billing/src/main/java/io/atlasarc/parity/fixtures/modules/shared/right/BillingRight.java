package io.atlasarc.parity.fixtures.modules.shared.right;

import io.atlasarc.parity.fixtures.modules.shared.left.BillingLeft;

public final class BillingRight {
    public int call(BillingLeft left) {
        return left.touch();
    }

    public int touch() {
        return 4;
    }
}
