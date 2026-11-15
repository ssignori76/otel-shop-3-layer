package com.commerce.synthetic.scenario;

/**
 * Canonical journey states and terminal failure states defined in Section 7.2 of the specification.
 */
public enum JourneyState {
    // Normal progression states
    STARTED(false),
    PAGE_LOADED(false),
    CART_CREATED(false),
    CART_READ(false),
    PAYMENT_AUTHORIZED(false),
    ORDER_PUBLISHED(false),
    FULFILLED(false),

    // Failure terminal states
    CHECKOUT_FAILED(true),
    DB_TIMEOUT(true),
    MERCHANT_TIMEOUT(true),
    PAYMENT_AUTHORIZED_NOT_PUBLISHED(true),
    FULFILLMENT_FAILED(true),
    INTERNAL_FAILURE(true);

    private final boolean failureState;

    JourneyState(boolean failureState) {
        this.failureState = failureState;
    }

    public boolean isFailureState() {
        return failureState;
    }

    public boolean canTransitionTo(JourneyState next) {
        if (next == null) {
            return false;
        }
        if (this.failureState) {
            return false;
        }
        if (next.failureState) {
            return true;
        }
        return switch (this) {
            case STARTED -> next == PAGE_LOADED || next == CART_CREATED;
            case PAGE_LOADED -> next == CART_CREATED;
            case CART_CREATED -> next == CART_READ;
            case CART_READ -> next == PAYMENT_AUTHORIZED;
            case PAYMENT_AUTHORIZED -> next == ORDER_PUBLISHED;
            case ORDER_PUBLISHED -> next == FULFILLED;
            case FULFILLED -> false;
            default -> false;
        };
    }
}
