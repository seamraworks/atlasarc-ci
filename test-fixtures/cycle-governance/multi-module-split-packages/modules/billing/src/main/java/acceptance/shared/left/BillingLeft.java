package acceptance.shared.left;

import acceptance.shared.right.BillingRight;

public final class BillingLeft {
    private final BillingRight right;

    public BillingLeft(BillingRight right) {
        this.right = right;
    }

    public BillingRight right() {
        return right;
    }
}
