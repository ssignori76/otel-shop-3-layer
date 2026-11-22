import { store } from "./state.js";

async function apiFetch(path, options = {}) {
    const journeyId = options.journeyId || store.newJourneyId();
    const headers = {
        "Accept": "application/json",
        "x-run-id": store.runId,
        "x-journey-id": journeyId,
        ...(options.headers || {})
    };

    if (options.body !== undefined) {
        headers["Content-Type"] = "application/json";
    }
    if (store.token && !options.skipAuth) {
        headers["Authorization"] = `Bearer ${store.token}`;
    }

    const response = await fetch(path, {
        method: options.method || "GET",
        headers,
        body: options.body !== undefined ? JSON.stringify(options.body) : undefined
    });

    const text = await response.text();
    let data = null;
    if (text) {
        try {
            data = JSON.parse(text);
        } catch {
            data = { message: text };
        }
    }

    if (!response.ok) {
        if (response.status === 401 && !options.skipAuth) {
            store.clearAuth();
        }
        const err = new Error(data?.message || `HTTP ${response.status}`);
        err.status = response.status;
        err.errorCode = data?.error_code || "HTTP_ERROR";
        err.payload = data;
        throw err;
    }

    return data;
}

export const api = {
    getProducts() {
        return apiFetch("/api/products", { skipAuth: true });
    },

    register(fullName, email, password) {
        return apiFetch("/api/auth/register", {
            method: "POST",
            skipAuth: true,
            body: { full_name: fullName, email, password }
        });
    },

    login(email, password) {
        return apiFetch("/api/auth/login", {
            method: "POST",
            skipAuth: true,
            body: { email, password }
        });
    },

    getMe() {
        return apiFetch("/api/auth/me");
    },

    async getActiveCart() {
        try {
            return await apiFetch("/api/carts/active");
        } catch (err) {
            if (err.status === 404) {
                return null;
            }
            throw err;
        }
    },

    createCart(sku, quantity = 1) {
        const journeyId = store.newJourneyId();
        return apiFetch("/api/carts", {
            method: "POST",
            journeyId,
            body: {
                run_id: store.runId,
                journey_id: journeyId,
                sku,
                quantity
            }
        });
    },

    getCart(cartId, journeyId) {
        return apiFetch(`/api/carts/${encodeURIComponent(cartId)}`, { journeyId });
    },

    addCartItem(cartId, sku, quantity = 1) {
        return apiFetch(`/api/carts/${encodeURIComponent(cartId)}/items`, {
            method: "POST",
            body: { sku, quantity }
        });
    },

    removeCartItem(cartId, sku) {
        return apiFetch(`/api/carts/${encodeURIComponent(cartId)}/items/${encodeURIComponent(sku)}`, {
            method: "DELETE"
        });
    },

    completeCart(cartId) {
        return apiFetch(`/api/carts/${encodeURIComponent(cartId)}/complete`, {
            method: "POST"
        });
    },

    checkout(cartId, totalCents, journeyId) {
        return apiFetch("/api/checkouts", {
            method: "POST",
            journeyId,
            body: {
                run_id: store.runId,
                journey_id: journeyId,
                cart_id: cartId,
                total_cents: totalCents
            }
        });
    },

    listOrders() {
        return apiFetch("/api/orders");
    },

    async getFulfillment(orderId) {
        try {
            return await apiFetch(`/api/fulfillments/${encodeURIComponent(orderId)}`);
        } catch (err) {
            if (err.status === 404) {
                return null;
            }
            throw err;
        }
    }
};
