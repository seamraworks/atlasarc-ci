package io.atlasarc.parity.fixtures.modules.shared.left;

import io.atlasarc.parity.fixtures.modules.shared.right.OrdersRight;

public final class OrdersLeft {
    public int call(OrdersRight right) {
        return right.touch();
    }

    public int touch() {
        return 1;
    }
}
