package demo.orders;

import demo.billing.BillingService;

public final class OrderService {
    public String placeOrder(BillingService billingService) {
        return "order:" + billingService.charge();
    }
}
