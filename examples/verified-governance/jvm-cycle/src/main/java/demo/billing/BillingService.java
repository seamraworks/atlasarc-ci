package demo.billing;

import demo.orders.OrderService;

public final class BillingService {
    public String charge() {
        return "paid";
    }

    public boolean canAudit(OrderService orderService) {
        return orderService != null;
    }
}
