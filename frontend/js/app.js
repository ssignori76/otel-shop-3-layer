import { api } from "./api.js";
import { store } from "./state.js";

const appEl = document.getElementById("app");
const navAuthEl = document.getElementById("nav-auth");
const cartBadgeEl = document.getElementById("cart-count-badge");
const footerSessionEl = document.getElementById("footer-session");
const toastContainer = document.getElementById("toast-container");

const PRODUCT_ICONS = {
    "SKU-WATCH-01": "⌚",
    "SKU-AUDIO-02": "🎧",
    "SKU-HUB-03": "🖥️"
};

let activePollTimer = null;

function formatPrice(cents) {
    return new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: "EUR"
    }).format((cents || 0) / 100);
}

function escapeHtml(str) {
    return String(str ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
}

function showToast(message, type = "info") {
    const el = document.createElement("div");
    el.className = `toast ${type === "error" ? "toast-error" : type === "success" ? "toast-success" : ""}`;
    el.textContent = message;
    toastContainer.appendChild(el);
    setTimeout(() => el.remove(), 4000);
}

function clearPolling() {
    if (activePollTimer) {
        clearTimeout(activePollTimer);
        activePollTimer = null;
    }
}

export function navigate(path) {
    clearPolling();
    if (window.location.pathname !== path) {
        window.history.pushState({}, "", path);
    }
    renderRoute();
}

function renderNavbar() {
    const path = window.location.pathname;
    document.querySelectorAll(".nav-link").forEach((link) => {
        const href = link.getAttribute("href");
        link.classList.toggle("active", href === path);
    });

    cartBadgeEl.textContent = String(store.cartItemCount());
    footerSessionEl.textContent = `run_id: ${store.runId.slice(0, 8)}...`;

    if (store.isAuthenticated()) {
        navAuthEl.innerHTML = `
            <span class="user-chip">${escapeHtml(store.user.full_name || store.user.email)}</span>
            <button class="btn btn-secondary btn-sm" id="btn-logout">Sign out</button>
        `;
        document.getElementById("btn-logout").addEventListener("click", () => {
            store.clearAuth();
            showToast("Signed out successfully", "info");
            navigate("/");
        });
    } else {
        navAuthEl.innerHTML = `
            <a href="/login" data-link class="btn btn-secondary btn-sm">Sign in</a>
            <a href="/register" data-link class="btn btn-primary btn-sm">Sign up</a>
        `;
    }
}

async function refreshActiveCart() {
    if (!store.isAuthenticated()) {
        store.cart = null;
        return;
    }
    try {
        store.cart = await api.getActiveCart();
    } catch (_) {
        store.cart = null;
    }
}

async function handleAddToCart(sku) {
    if (!store.isAuthenticated()) {
        store.pendingSku = sku;
        showToast("Please sign in or create an account to add items to your cart", "info");
        navigate("/login");
        return;
    }

    try {
        if (!store.cart || store.cart.status === "CHECKED_OUT") {
            store.cart = await api.createCart(sku, 1);
        } else {
            store.cart = await api.addCartItem(store.cart.cart_id, sku, 1);
        }
        renderNavbar();
        showToast("Product added to cart!", "success");
    } catch (err) {
        showToast(`Failed to add item to cart: ${err.message}`, "error");
    }
}

async function renderHome() {
    appEl.innerHTML = `<div class="loading-box">Loading product catalog from backend...</div>`;
    try {
        store.products = await api.getProducts();
    } catch (err) {
        appEl.innerHTML = `<div class="alert alert-error">Unable to load products: ${escapeHtml(err.message)}</div>`;
        return;
    }

    const cardsHtml = store.products.map((p) => `
        <article class="card">
            <div class="product-header">
                <span class="product-category">${escapeHtml(p.category)}</span>
                <span class="product-badge">${escapeHtml(p.badge)}</span>
            </div>
            <div class="product-icon" aria-hidden="true">${PRODUCT_ICONS[p.sku] || "📦"}</div>
            <h2 class="product-title">${escapeHtml(p.name)}</h2>
            <p class="product-desc">${escapeHtml(p.description)}</p>
            <div class="product-footer">
                <span class="price">${formatPrice(p.price_cents)}</span>
                <button class="btn btn-primary" data-add-sku="${escapeHtml(p.sku)}">
                    Add to cart
                </button>
            </div>
        </article>
    `).join("");

    appEl.innerHTML = `
        <section class="hero">
            <span class="hero-eyebrow">3-Layer Distributed Observability &amp; RUM Demo</span>
            <h1>OTel Shop — E-Commerce Storefront</h1>
            <p>
                Browse the 3 demo products, sign in with your account (JWT authentication),
                and place an order to exercise the entire distributed microservices stack.
            </p>
            <div class="hero-pipeline" aria-label="Microservices call flow">
                <span class="pipe-step">Apache</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">API Gateway</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">Auth</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">Cart</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">Checkout</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">Merchant</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">RabbitMQ</span>
                <span class="pipe-arrow" aria-hidden="true">&rarr;</span>
                <span class="pipe-step">Fulfillment</span>
            </div>
        </section>
        <section class="products-grid" aria-label="Demo products">
            ${cardsHtml}
        </section>
    `;

    appEl.querySelectorAll("[data-add-sku]").forEach((btn) => {
        btn.addEventListener("click", () => handleAddToCart(btn.dataset.addSku));
    });
}

function renderLogin() {
    const expiredBanner = store.sessionExpired
        ? `<div class="alert alert-error">Your session has expired. Please sign in again to continue.</div>`
        : "";
    const prefillEmail = escapeHtml(store.prefillEmail || "");
    store.sessionExpired = false;
    store.prefillEmail = "";

    appEl.innerHTML = `
        <section class="card auth-card">
            <h1>Sign in to your account</h1>
            <p class="muted">Sign in to manage your shopping cart and confirm orders.</p>
            ${expiredBanner}
            <div id="auth-error"></div>
            <form id="login-form">
                <div class="form-group">
                    <label for="login-email">Email</label>
                    <input id="login-email" type="email" required autocomplete="email" value="${prefillEmail}" placeholder="name@example.com">
                </div>
                <div class="form-group">
                    <label for="login-password">Password</label>
                    <input id="login-password" type="password" required autocomplete="current-password" placeholder="••••••••">
                </div>
                <button type="submit" class="btn btn-primary" style="width: 100%;" id="btn-login-submit">Sign in</button>
            </form>

            <div class="demo-hint">
                <div>
                    <strong>Demo Account</strong><br>
                    <span class="mono">demo@otelshop.local</span>
                </div>
                <button type="button" class="btn btn-secondary btn-sm" id="btn-fill-demo">Fill Demo</button>
            </div>

            <p class="muted" style="text-align: center;">
                <a href="/reset-password" data-link>Forgot password?</a>
                &middot;
                Don't have an account? <a href="/register" data-link>Create one now</a>
            </p>
        </section>
    `;

    document.getElementById("btn-fill-demo").addEventListener("click", () => {
        document.getElementById("login-email").value = "demo@otelshop.local";
        document.getElementById("login-password").value = "DemoPass123!";
    });

    document.getElementById("login-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        const email = document.getElementById("login-email").value.trim();
        const password = document.getElementById("login-password").value;
        const submitBtn = document.getElementById("btn-login-submit");
        const errBox = document.getElementById("auth-error");
        errBox.innerHTML = "";
        submitBtn.disabled = true;

        try {
            const resp = await api.login(email, password);
            store.setAuth(resp.access_token, resp.user);
            await refreshActiveCart();
            showToast(`Welcome back, ${resp.user.full_name}!`, "success");

            if (store.pendingSku) {
                const sku = store.pendingSku;
                store.pendingSku = null;
                await handleAddToCart(sku);
                navigate("/cart");
            } else {
                navigate("/");
            }
        } catch (err) {
            errBox.innerHTML = `<div class="alert alert-error">${escapeHtml(err.message)}</div>`;
        } finally {
            submitBtn.disabled = false;
        }
    });
}

function renderRegister() {
    appEl.innerHTML = `
        <section class="card auth-card">
            <h1>Create a new account</h1>
            <p class="muted">Sign up to save your cart and track order fulfillment in real time.</p>
            <div id="reg-error"></div>
            <form id="register-form">
                <div class="form-group">
                    <label for="reg-name">Full Name</label>
                    <input id="reg-name" type="text" required minlength="2" placeholder="Jane Doe">
                </div>
                <div class="form-group">
                    <label for="reg-email">Email</label>
                    <input id="reg-email" type="email" required autocomplete="email" placeholder="jane.doe@example.com">
                </div>
                <div class="form-group">
                    <label for="reg-password">Password (minimum 8 characters)</label>
                    <input id="reg-password" type="password" required minlength="8" autocomplete="new-password" placeholder="••••••••">
                </div>
                <button type="submit" class="btn btn-primary" style="width: 100%;" id="btn-reg-submit">Create account</button>
            </form>
            <p class="muted" style="text-align: center;">
                Already have an account? <a href="/login" data-link>Sign in</a>
            </p>
        </section>
    `;

    document.getElementById("register-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        const fullName = document.getElementById("reg-name").value.trim();
        const email = document.getElementById("reg-email").value.trim();
        const password = document.getElementById("reg-password").value;
        const submitBtn = document.getElementById("btn-reg-submit");
        const errBox = document.getElementById("reg-error");
        errBox.innerHTML = "";
        submitBtn.disabled = true;

        try {
            const resp = await api.register(fullName, email, password);
            store.setAuth(resp.access_token, resp.user);
            store.cart = null;
            showToast("Account created successfully!", "success");

            if (store.pendingSku) {
                const sku = store.pendingSku;
                store.pendingSku = null;
                await handleAddToCart(sku);
                navigate("/cart");
            } else {
                navigate("/");
            }
        } catch (err) {
            errBox.innerHTML = `<div class="alert alert-error">${escapeHtml(err.message)}</div>`;
        } finally {
            submitBtn.disabled = false;
        }
    });
}

async function renderCart() {
    if (!store.isAuthenticated()) {
        showToast("Please sign in to access your cart", "info");
        navigate("/login");
        return;
    }

    appEl.innerHTML = `<div class="loading-box">Loading cart...</div>`;
    await refreshActiveCart();
    renderNavbar();

    const cart = store.cart;
    if (!cart || !cart.items || cart.items.length === 0) {
        appEl.innerHTML = `
            <section class="card">
                <h1>Your Cart</h1>
                <p class="muted">Your cart is currently empty. Pick one of the 3 demo products from the storefront.</p>
                <div>
                    <a href="/" data-link class="btn btn-primary">Browse Products</a>
                </div>
            </section>
        `;
        return;
    }

    const itemsHtml = cart.items.map((item) => `
        <div class="cart-row">
            <div>
                <strong>${escapeHtml(item.name)}</strong>
                <div class="muted mono">${escapeHtml(item.sku)} &middot; Qty: ${item.quantity} &times; ${formatPrice(item.unit_price_cents)}</div>
            </div>
            <div style="display:flex; align-items:center; gap:12px;">
                <strong>${formatPrice(item.line_total_cents)}</strong>
                <button class="btn btn-danger btn-sm" data-remove-sku="${escapeHtml(item.sku)}">Remove</button>
            </div>
        </div>
    `).join("");

    appEl.innerHTML = `
        <section class="card">
            <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:8px;">
                <h1>Your Cart</h1>
                <span class="muted mono">cart_id: ${escapeHtml(cart.cart_id)}</span>
            </div>
            <div id="checkout-feedback"></div>
            <div>${itemsHtml}</div>
            <div class="summary-bar">
                <div>
                    <span class="muted">Order total</span>
                    <div class="price">${formatPrice(cart.total_cents)}</div>
                </div>
                <button class="btn btn-primary" id="btn-confirm-checkout">Confirm Order</button>
            </div>
        </section>
    `;

    appEl.querySelectorAll("[data-remove-sku]").forEach((btn) => {
        btn.addEventListener("click", async () => {
            try {
                store.cart = await api.removeCartItem(cart.cart_id, btn.dataset.removeSku);
                renderCart();
            } catch (err) {
                showToast(`Failed to remove item: ${err.message}`, "error");
            }
        });
    });

    document.getElementById("btn-confirm-checkout").addEventListener("click", async (e) => {
        const btn = e.currentTarget;
        const feedback = document.getElementById("checkout-feedback");
        btn.disabled = true;
        btn.textContent = "Authorizing payment and submitting order...";
        feedback.innerHTML = "";

        try {
            const journeyId = store.newJourneyId();
            // Step 1: read cart (triggers CART_READ transition)
            const freshCart = await api.getCart(cart.cart_id, journeyId);
            // Step 2: checkout (DB + Merchant + RabbitMQ publish)
            const orderResp = await api.checkout(freshCart.cart_id, freshCart.total_cents || 0, journeyId);
            // Step 3: mark cart checked out
            await api.completeCart(freshCart.cart_id);

            store.cart = null;
            store.lastCheckout = orderResp;
            renderNavbar();
            showToast("Order confirmed! Sending to Fulfillment...", "success");
            navigate("/orders");
        } catch (err) {
            feedback.innerHTML = `<div class="alert alert-error">Checkout failed: ${escapeHtml(err.message)}</div>`;
            btn.disabled = false;
            btn.textContent = "Confirm Order";
        }
    });
}

async function renderOrders(pollAttempt = 0) {
    if (!store.isAuthenticated()) {
        showToast("Please sign in to view your orders", "info");
        navigate("/login");
        return;
    }

    if (pollAttempt === 0) {
        appEl.innerHTML = `<div class="loading-box">Loading orders and fulfillment status...</div>`;
    }

    let orders;
    try {
        orders = await api.listOrders();
    } catch (err) {
        appEl.innerHTML = `<div class="alert alert-error">Failed to load orders: ${escapeHtml(err.message)}</div>`;
        return;
    }

    const enriched = await Promise.all(
        orders.map(async (order) => {
            const fulfillment = await api.getFulfillment(order.order_id);
            return { ...order, fulfillment };
        })
    );

    const hasPending = enriched.some((o) => o.status === "PUBLISHED" && !o.fulfillment);

    const bannerHtml = store.lastCheckout ? `
        <div class="alert alert-success">
            <strong>Latest order confirmed!</strong>
            <div class="kv-list">
                <span class="kv-label">Order ID</span>
                <span class="mono kv-value">${escapeHtml(store.lastCheckout.order_id)}</span>
                <span class="kv-label">Payment Auth</span>
                <span class="mono kv-value">${escapeHtml(store.lastCheckout.payment_authorization_id)}</span>
                <span class="kv-label">Trace ID</span>
                <span class="mono kv-value">${escapeHtml(store.lastCheckout.trace_id || "n/a")}</span>
            </div>
        </div>
    ` : "";

    const rowsHtml = enriched.length === 0
        ? `<p class="muted">You have not placed any orders yet. Visit the storefront to get started.</p>`
        : enriched.map((o) => {
            const finalStatus = o.fulfillment ? o.fulfillment.status : o.status;
            const pillClass = finalStatus === "FULFILLED"
                ? "status-fulfilled"
                : finalStatus === "PUBLISHED"
                    ? "status-published"
                    : "status-error";
            return `
                <div class="order-row">
                    <div class="order-meta">
                        <div><strong>Order:</strong> <span class="mono">${escapeHtml(o.order_id)}</span></div>
                        <div class="muted"><strong>Created:</strong> ${escapeHtml(new Date(o.created_at).toLocaleString("en-US"))}</div>
                        <div class="muted"><strong>Total:</strong> <strong>${formatPrice(o.total_cents)}</strong></div>
                        <div class="muted"><strong>Payment Auth:</strong> <span class="mono">${escapeHtml(o.payment_authorization_id)}</span></div>
                        ${o.fulfillment ? `<div class="muted"><strong>Fulfillment ID:</strong> <span class="mono">${escapeHtml(o.fulfillment.fulfillment_id)}</span></div>` : ""}
                    </div>
                    <div>
                        <span class="status-pill ${pillClass}">${escapeHtml(finalStatus)}</span>
                    </div>
                </div>
            `;
        }).join("");

    appEl.innerHTML = `
        <section class="card">
            <div style="display:flex; justify-content:space-between; align-items:center;">
                <h1>My Orders</h1>
                <button class="btn btn-secondary btn-sm" id="btn-refresh-orders">Refresh status</button>
            </div>
            ${bannerHtml}
            <div>${rowsHtml}</div>
        </section>
    `;

    document.getElementById("btn-refresh-orders").addEventListener("click", () => renderOrders(0));

    if (hasPending && pollAttempt < 10) {
        activePollTimer = setTimeout(() => renderOrders(pollAttempt + 1), 1500);
    }
}

function renderResetPassword() {
    appEl.innerHTML = `
        <section class="card auth-card">
            <h1>Reset your password</h1>
            <p class="muted">Enter your account email and choose a new password (minimum 8 characters).</p>
            <div id="reset-error"></div>
            <form id="reset-form">
                <div class="form-group">
                    <label for="reset-email">Email</label>
                    <input id="reset-email" type="email" required autocomplete="email" placeholder="name@example.com">
                </div>
                <div class="form-group">
                    <label for="reset-password">New Password (minimum 8 characters)</label>
                    <input id="reset-password" type="password" required minlength="8" autocomplete="new-password" placeholder="••••••••">
                </div>
                <div class="form-group">
                    <label for="reset-password-confirm">Confirm New Password</label>
                    <input id="reset-password-confirm" type="password" required minlength="8" autocomplete="new-password" placeholder="••••••••">
                </div>
                <button type="submit" class="btn btn-primary" style="width: 100%;" id="btn-reset-submit">Update password</button>
            </form>
            <p class="muted" style="text-align: center;">
                Remembered your password? <a href="/login" data-link>Back to Sign in</a>
            </p>
        </section>
    `;

    document.getElementById("reset-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        const email = document.getElementById("reset-email").value.trim();
        const newPassword = document.getElementById("reset-password").value;
        const confirmPassword = document.getElementById("reset-password-confirm").value;
        const submitBtn = document.getElementById("btn-reset-submit");
        const errBox = document.getElementById("reset-error");
        errBox.innerHTML = "";

        if (newPassword !== confirmPassword) {
            errBox.innerHTML = `<div class="alert alert-error">Passwords do not match.</div>`;
            return;
        }

        submitBtn.disabled = true;
        try {
            await api.resetPassword(email, newPassword);
            store.prefillEmail = email;
            showToast("Password updated! Please sign in with your new password.", "success");
            navigate("/login");
        } catch (err) {
            errBox.innerHTML = `<div class="alert alert-error">${escapeHtml(err.message)}</div>`;
        } finally {
            submitBtn.disabled = false;
        }
    });
}

async function renderRoute() {
    renderNavbar();
    const path = window.location.pathname;
    if (path === "/login") {
        renderLogin();
    } else if (path === "/register") {
        renderRegister();
    } else if (path === "/reset-password") {
        renderResetPassword();
    } else if (path === "/cart") {
        await renderCart();
    } else if (path === "/orders") {
        await renderOrders(0);
    } else {
        await renderHome();
    }
}

// Redirect to login with explicit notice whenever a JWT expires (HTTP 401)
window.addEventListener("auth:expired", () => {
    showToast("Your session has expired. Please sign in again.", "error");
    navigate("/login");
});

// Intercept SPA links
document.addEventListener("click", (e) => {
    const link = e.target.closest("a[data-link]");
    if (!link) return;
    e.preventDefault();
    navigate(link.getAttribute("href"));
});

window.addEventListener("popstate", () => renderRoute());

// Initial bootstrap
(async function init() {
    if (store.isAuthenticated()) {
        try {
            const me = await api.getMe();
            store.setAuth(store.token, me);
            await refreshActiveCart();
        } catch (_) {
            store.clearAuth();
        }
    }
    await renderRoute();
})();
