package io.atlasarc.archunit.fixtures.governed.billing;

import io.atlasarc.archunit.fixtures.governed.orders.OrderService;

public class Invoice {
    private final OrderService orders = new OrderService();

    public OrderService orders() {
        return orders;
    }
}
