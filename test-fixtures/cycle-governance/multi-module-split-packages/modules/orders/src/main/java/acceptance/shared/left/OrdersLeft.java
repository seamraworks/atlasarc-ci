package acceptance.shared.left;

import acceptance.shared.right.OrdersRight;

public final class OrdersLeft {
    private final OrdersRight right;

    public OrdersLeft(OrdersRight right) {
        this.right = right;
    }

    public OrdersRight right() {
        return right;
    }
}
