package io.atlasarc.parity.fixtures.modules.shared.right;

import io.atlasarc.parity.fixtures.modules.shared.left.OrdersLeft;

public final class OrdersRight {
    public int call(OrdersLeft left) {
        return left.touch();
    }

    public int touch() {
        return 2;
    }
}
