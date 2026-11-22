const TOKEN_KEY = "otelshop_jwt_token";
const USER_KEY = "otelshop_user_profile";
const RUN_ID_KEY = "otelshop_run_id";

function generateUuid() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return window.crypto.randomUUID();
    }
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        const v = c === "x" ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}

function getOrCreateRunId() {
    let runId = sessionStorage.getItem(RUN_ID_KEY);
    if (!runId) {
        runId = generateUuid();
        sessionStorage.setItem(RUN_ID_KEY, runId);
    }
    return runId;
}

export const store = {
    runId: getOrCreateRunId(),
    token: localStorage.getItem(TOKEN_KEY) || null,
    user: JSON.parse(localStorage.getItem(USER_KEY) || "null"),
    products: [],
    cart: null,
    lastCheckout: null,
    pendingSku: null,
    sessionExpired: false,
    prefillEmail: "",

    newJourneyId() {
        return generateUuid();
    },

    isAuthenticated() {
        return Boolean(this.token && this.user);
    },

    setAuth(token, user) {
        this.token = token;
        this.user = user;
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_KEY, JSON.stringify(user));

        // Dynatrace RUM user identification hook (no-op if RUM is not loaded)
        if (window.dtrum && typeof window.dtrum.identifyUser === "function" && user?.email) {
            try {
                window.dtrum.identifyUser(user.email);
            } catch (_) {
                // ignore RUM hook errors
            }
        }
    },

    clearAuth() {
        this.token = null;
        this.user = null;
        this.cart = null;
        this.lastCheckout = null;
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_KEY);

        if (window.dtrum && typeof window.dtrum.endSession === "function") {
            try {
                window.dtrum.endSession();
            } catch (_) {
                // ignore RUM hook errors
            }
        }
    },

    cartItemCount() {
        if (!this.cart || !Array.isArray(this.cart.items)) {
            return 0;
        }
        return this.cart.items.reduce((acc, item) => acc + (item.quantity || 0), 0);
    }
};
