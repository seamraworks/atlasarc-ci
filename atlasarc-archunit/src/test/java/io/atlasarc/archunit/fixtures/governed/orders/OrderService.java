package io.atlasarc.archunit.fixtures.governed.orders;

import io.atlasarc.archunit.fixtures.governed.billing.Invoice;

public class OrderService {
    public Invoice invoice() {
        return new Invoice();
    }

    public OrderService roundTrip() {
        return new Invoice().orders();
    }
}
