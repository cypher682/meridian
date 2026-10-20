import Keycloak from "/vendor/keycloak.js";
import * as offline from "./offline.js";

const KEYCLOAK_URL = "http://localhost:8084";
const KEYCLOAK_REALM = "meridian";
const KEYCLOAK_CLIENT_ID = "meridian-ui";
const REFRESH_INTERVAL_MS = 30000;
const REFRESH_MIN_VALIDITY_S = 60;
const OFFLINE_SYNC_ENABLED = true;
const MAX_SYNC_ATTEMPTS = 20;

let keycloak = null;
let refreshTimer = null;

const TEST_NAMES = {
  CBC: "Complete blood count",
  BMP: "Basic metabolic panel",
  A1C: "Hemoglobin A1c",
  LIPID: "Lipid panel",
  TSH: "Thyroid stimulating hormone",
  CRP: "C-reactive protein"
};

const STATUS_LABELS = {
  ordered: "Ordered",
  collected: "Collected",
  resulted: "Resulted",
  released: "Released",
  cancelled: "Cancelled",
  open: "Open",
  paid: "Paid",
  void: "Void"
};

const state = {
  view: "front",
  user: null,
  roles: [],
  patient: null,
  searchResults: [],
  lastQuery: "",
  encounters: [],
  selectedEncounterId: null,
  patientOrders: [],
  labOrders: [],
  selectedOrder: null,
  amendments: [],
  labFilter: "",
  billingInvoices: [],
  selectedInvoice: null,
  pharmacyItems: [],
  dispenses: [],
  toastTimer: null,
  facilityId: null,
  online: true,
  flushInProgress: false,
  flushRequested: false,
  forceRequested: false,
  syncSummary: { pending: 0, syncing: 0, failed: 0 }
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;"
})[character]);

const shortId = (value) => (value ? String(value).slice(0, 8) : "—");

function formatDate(value) {
  if (!value) {
    return "—";
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [year, month, day] = value.split("-").map(Number);
    return new Date(year, month - 1, day).toLocaleDateString("en-GB", {
      day: "numeric",
      month: "short",
      year: "numeric"
    });
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}

function formatDateTime(value) {
  if (!value) {
    return "—";
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString("en-GB", {
        day: "numeric",
        month: "short",
        hour: "2-digit",
        minute: "2-digit"
      });
}

function patientName(patient) {
  return [patient.familyName, patient.givenName].filter(Boolean).join(", ");
}

function patientMeta(patient) {
  return [formatDate(patient.dateOfBirth), patient.phone || "No phone", `Chart ${shortId(patient.id)}`].join(" · ");
}

function testName(code) {
  return TEST_NAMES[code] || code;
}

function statusBadge(status) {
  return `<span class="status" data-status="${esc(status)}">${esc(STATUS_LABELS[status] || status)}</span>`;
}

function money(cents) {
  return `$${(Number(cents || 0) / 100).toFixed(2)}`;
}

function vitalsSummary(vitals) {
  if (!vitals || typeof vitals !== "object") {
    return "No vitals";
  }
  const parts = [];
  if (vitals.temperatureC) {
    parts.push(`${vitals.temperatureC} °C`);
  }
  if (vitals.heartRate) {
    parts.push(`${vitals.heartRate} bpm`);
  }
  if (vitals.bloodPressure) {
    parts.push(vitals.bloodPressure);
  }
  return parts.length ? parts.join(" · ") : "No vitals";
}

function actor() {
  return state.user || "unknown";
}

function newId() {
  if (window.crypto && typeof window.crypto.randomUUID === "function") {
    return window.crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (character) => {
    const value = Math.floor(Math.random() * 16);
    return (character === "x" ? value : (value & 0x3) | 0x8).toString(16);
  });
}

function offlineError(message) {
  const error = new Error(message);
  error.offline = true;
  return error;
}

function isOfflineError(error) {
  return !navigator.onLine || Boolean(error && (
    error.offline || error.status === 408 || error.status === 429 || error.status >= 500
  ));
}

async function api(path, options = {}) {
  if (!keycloak || !keycloak.authenticated) {
    throw new Error("Sign in is required.");
  }

  try {
    await keycloak.updateToken(30);
  } catch {
    if (keycloak.isTokenExpired(0)) {
      if (navigator.onLine) {
        keycloak.login();
      }
      throw offlineError("You are offline. Sign in again when the network returns.");
    }
  }

  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  headers.set("Authorization", `Bearer ${keycloak.token}`);

  let response;
  try {
    response = await fetch(path, { ...options, headers });
  } catch {
    throw offlineError("The API is unreachable. Changes will sync when it returns.");
  }
  if (response.status === 401) {
    if (navigator.onLine) {
      keycloak.login();
    }
    throw new Error("Session expired. Sign in again.");
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const data = await response.json();
      if (data && data.error) {
        message = data.error;
      }
    } catch {
      // Keep the status-based message.
    }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
}

function showToast(message, kind = "ok") {
  const toast = $("#toast");
  toast.textContent = message;
  toast.dataset.kind = kind;
  toast.classList.add("is-visible");
  window.clearTimeout(state.toastTimer);
  state.toastTimer = window.setTimeout(() => {
    toast.classList.remove("is-visible");
  }, 4200);
}

function reportError(error) {
  showToast(error instanceof Error ? error.message : "Something went wrong", "error");
}

async function checkHealth() {
  const connection = $("#connection");
  const wasOnline = state.online;
  try {
    const response = await fetch("/api/health");
    if (!response.ok) {
      throw new Error("unhealthy");
    }
    state.online = true;
    connection.dataset.state = "online";
    connection.textContent = "API ready";
  } catch {
    state.online = false;
    connection.dataset.state = "offline";
    connection.textContent = "API unavailable";
  }
  renderSyncStatus();
  if (!wasOnline && state.online && keycloak && keycloak.authenticated) {
    flushOutbox({ force: true });
  }
}

function syncLabel(type) {
  return {
    "patient.register": "Patient",
    "encounter.create": "Encounter",
    "billing.invoice.create": "Invoice",
    "billing.charge.create": "Charge",
    "billing.invoice.pay": "Payment"
  }[type] || "Change";
}

function syncBadge(item) {
  if (!item) {
    return "";
  }
  if (item._failed) {
    return `<span class="sync-badge" data-state="failed">Sync failed</span>`;
  }
  if (item._pending) {
    return `<span class="sync-badge">Pending sync</span>`;
  }
  return "";
}

function replaceById(list, item) {
  if (!item || !item.id) {
    return;
  }
  const index = list.findIndex((candidate) => candidate.id === item.id);
  if (index >= 0) {
    list[index] = item;
  } else {
    list.unshift(item);
  }
}

function mergeInvoiceData(data) {
  if (!data || !data.id) {
    return data;
  }
  const local = (state.selectedInvoice && state.selectedInvoice.id === data.id)
    ? state.selectedInvoice
    : state.billingInvoices.find((invoice) => invoice.id === data.id);
  if (!local) {
    return data;
  }

  const serverCharges = data.charges || [];
  const pendingCharges = (local.charges || []).filter((charge) => (
    charge._pending && !serverCharges.some((serverCharge) => serverCharge.id === charge.id)
  ));
  const merged = {
    ...data,
    charges: [...serverCharges, ...pendingCharges],
    _pending: pendingCharges.length > 0
  };
  if (pendingCharges.length) {
    merged.totalCents = Number(data.totalCents || 0)
      + pendingCharges.reduce((sum, charge) => sum + Number(charge.amountCents || 0), 0);
  }
  if (local.status === "paid" && data.status === "open") {
    merged.status = "paid";
    merged.paidAt = local.paidAt;
    merged.paidBy = local.paidBy;
    merged._pending = true;
  }
  return merged;
}

async function refreshSyncSummary() {
  if (!state.facilityId) {
    state.syncSummary = { pending: 0, syncing: 0, failed: 0 };
    renderSyncStatus();
    return;
  }
  try {
    state.syncSummary = await offline.outboxSummary(state.facilityId);
  } catch {
    state.syncSummary = { pending: 0, syncing: 0, failed: 0 };
  }
  renderSyncStatus();
}

function renderSyncStatus() {
  const pill = $("#sync-pill");
  const button = $("#sync-now");
  if (!pill) {
    return;
  }
  const { pending = 0, syncing = 0, failed = 0 } = state.syncSummary;
  const queued = pending + syncing + failed;
  let label = state.online ? "Online" : "Offline";
  let visualState = state.online ? "online" : "offline";
  if (syncing) {
    label = `Syncing ${syncing}`;
    visualState = "pending";
  } else if (failed) {
    label = state.online ? `${failed} failed` : `Offline · ${failed} failed`;
    visualState = "offline";
  } else if (pending) {
    label = state.online ? `${pending} pending` : `Offline · ${pending} pending`;
    visualState = state.online ? "pending" : "offline";
  } else if (!state.online) {
    label = "Offline";
    visualState = "offline";
  }
  pill.dataset.state = visualState;
  pill.textContent = label;
  if (button) {
    button.hidden = queued === 0;
    button.disabled = !state.online || state.flushInProgress;
    button.textContent = state.flushInProgress ? "Syncing…" : failed ? "Retry sync" : "Sync now";
  }
}

async function postOutboxEntry(entry) {
  try {
    await keycloak.updateToken(30);
  } catch {
    if (keycloak.isTokenExpired(0)) {
      if (navigator.onLine) {
        keycloak.login();
      }
      throw offlineError("Sign in again to sync queued changes.");
    }
  }

  const headers = new Headers();
  headers.set("Authorization", `Bearer ${keycloak.token}`);
  headers.set("X-Meridian-Captured-At", entry.capturedAt);
  let body;
  if (entry.body) {
    headers.set("Content-Type", "application/json");
    body = JSON.stringify(entry.body);
  }

  let response;
  try {
    response = await fetch(entry.path, { method: "POST", headers, body });
  } catch {
    throw offlineError("The API is unreachable. Queued changes are safe.");
  }
  if (response.status === 401) {
    if (navigator.onLine) {
      keycloak.login();
    }
    const error = new Error("Session expired. Sign in again.");
    error.status = 401;
    throw error;
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const data = await response.json();
      if (data && data.error) {
        message = data.error;
      }
    } catch {
      // Keep the status-based message.
    }
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) {
    return null;
  }
  return response.json();
}

async function applySyncedEntry(entry, data) {
  if (!data) {
    return;
  }
  if (entry.type === "patient.register") {
    await offline.cachePatient(data);
    replaceById(state.searchResults, data);
    if (state.patient && state.patient.id === data.id) {
      state.patient = data;
    }
    renderPatientResults();
    if (state.view === "clinician") {
      renderClinician();
    }
  }
  if (entry.type === "encounter.create") {
    await offline.cacheEncounter(state.facilityId, data);
    if (state.patient && state.patient.id === data.patientId) {
      replaceById(state.encounters, data);
      state.encounters.sort((left, right) => String(right.createdAt).localeCompare(String(left.createdAt)));
      if (state.selectedEncounterId === data.id) {
        state.selectedEncounterId = data.id;
      }
      renderEncounterList();
      renderChart();
    }
    if (state.view === "clinician") {
      renderClinician();
    }
  }
  if (entry.type === "billing.invoice.create" || entry.type === "billing.charge.create" || entry.type === "billing.invoice.pay") {
    const merged = mergeInvoiceData(data);
    await offline.cacheInvoice(state.facilityId, merged);
    replaceById(state.billingInvoices, merged);
    if (state.selectedInvoice && state.selectedInvoice.id === merged.id) {
      state.selectedInvoice = merged;
    }
    renderBillingInvoices();
  }
}

function markFailed(entry) {
  const targetId = entry.targetId || (entry.body && entry.body.id);
  if (entry.type === "patient.register") {
    const patient = state.searchResults.find((candidate) => candidate.id === targetId)
      || (state.patient && state.patient.id === targetId ? state.patient : null);
    if (patient) {
      patient._failed = true;
      patient._pending = false;
    }
    renderPatientResults();
  }
  if (entry.type === "encounter.create") {
    const encounter = state.encounters.find((candidate) => candidate.id === targetId);
    if (encounter) {
      encounter._failed = true;
      encounter._pending = false;
    }
    renderEncounterList();
  }
  if (entry.type === "billing.invoice.create" || entry.type === "billing.charge.create" || entry.type === "billing.invoice.pay") {
    const invoice = state.billingInvoices.find((candidate) => candidate.id === (entry.parentId || targetId));
    if (invoice) {
      invoice._failed = true;
      invoice._pending = false;
    }
    if (state.selectedInvoice && state.selectedInvoice.id === (entry.parentId || targetId)) {
      state.selectedInvoice._failed = true;
      state.selectedInvoice._pending = false;
    }
    renderBillingInvoices();
    renderBillingDetail();
  }
}

async function flushOutbox(options = {}) {
  const force = Boolean(options.force);
  if (!OFFLINE_SYNC_ENABLED || !keycloak || !keycloak.authenticated || !state.facilityId) {
    return;
  }
  if (state.flushInProgress) {
    state.flushRequested = true;
    state.forceRequested = state.forceRequested || force;
    return;
  }
  if (!navigator.onLine) {
    await refreshSyncSummary();
    return;
  }

  state.flushInProgress = true;
  renderSyncStatus();
  try {
    if (navigator.locks && typeof navigator.locks.request === "function") {
      await navigator.locks.request("meridian-outbox-sync", () => drainOutbox(force));
    } else {
      await drainOutbox(force);
    }
  } catch (error) {
    showToast(`Sync failed: ${error.message}`, "error");
  } finally {
    state.flushInProgress = false;
    await refreshSyncSummary();
    renderSyncStatus();
    if (state.flushRequested) {
      const forceNext = state.forceRequested;
      state.flushRequested = false;
      state.forceRequested = false;
      flushOutbox({ force: forceNext });
    }
  }
}

async function drainOutbox(force) {
  await offline.resetStaleSyncing(state.facilityId);
  let entries = await offline.listOutbox(state.facilityId);
  if (entries.some((entry) => entry.actor !== actor())) {
    showToast("Queued changes belong to another user. Sign in as that user or clear offline data.", "error");
    return;
  }
  if (force) {
    for (const entry of entries) {
      if (entry.status === "failed" || entry.status === "pending") {
        await offline.updateOutbox(entry.operationId, {
          status: "pending",
          attempts: 0,
          nextAttemptAt: 0,
          lastError: null
        });
      }
    }
    entries = await offline.listOutbox(state.facilityId);
  }

  for (const entry of entries) {
    if (entry.status === "failed") {
      break;
    }
    if (entry.status !== "pending") {
      continue;
    }
    if (!force && entry.nextAttemptAt && entry.nextAttemptAt > Date.now()) {
      break;
    }

    await offline.updateOutbox(entry.operationId, { status: "syncing" });
    await refreshSyncSummary();
    try {
      const data = await postOutboxEntry(entry);
      await applySyncedEntry(entry, data);
      await offline.removeOutbox(entry.operationId);
      await refreshSyncSummary();
    } catch (error) {
      const attempts = (entry.attempts || 0) + 1;
      if (error.status === 401) {
        await offline.updateOutbox(entry.operationId, {
          status: "pending",
          attempts,
          lastError: error.message
        });
        showToast("Sign in again to sync queued changes.", "error");
        break;
      }
      if (isOfflineError(error) && attempts < MAX_SYNC_ATTEMPTS) {
        const delay = Math.min(60000, 3000 * 2 ** Math.min(attempts, 4));
        await offline.updateOutbox(entry.operationId, {
          status: "pending",
          attempts,
          nextAttemptAt: Date.now() + delay,
          lastError: error.message
        });
        break;
      }
      const failed = {
        ...entry,
        status: "failed",
        attempts,
        lastError: error.message
      };
      await offline.updateOutbox(entry.operationId, failed);
      markFailed(failed);
      showToast(`${syncLabel(entry.type)} could not sync: ${error.message}`, "error");
      break;
    }
    await refreshSyncSummary();
  }
}

async function enqueueOperation(entry) {
  const queued = {
    operationId: newId(),
    status: "pending",
    attempts: 0,
    nextAttemptAt: 0,
    lastError: null,
    createdAt: new Date().toISOString(),
    capturedAt: new Date().toISOString(),
    facilityId: state.facilityId,
    actor: actor(),
    ...entry
  };
  await offline.enqueueOperation(queued);
  await refreshSyncSummary();
  if (navigator.onLine) {
    showToast(`${syncLabel(queued.type)} saved. Syncing…`);
    flushOutbox();
  } else {
    showToast(`${syncLabel(queued.type)} saved offline. It will sync automatically.`, "warn");
  }
  return queued;
}

function hasAnyRole(...roles) {
  return roles.some((role) => state.roles.includes(role));
}

function canView(view) {
  const item = $(`.nav-item[data-view="${view}"]`);
  if (!item) {
    return false;
  }
  const roles = (item.dataset.roles || "").split(",").map((role) => role.trim()).filter(Boolean);
  return roles.length === 0 || roles.some((role) => state.roles.includes(role));
}

function firstAllowedView() {
  return ["front", "clinician", "lab", "billing", "pharmacy", "audit"].find(canView) || "denied";
}

function showDenied() {
  state.view = "denied";
  $$(".view").forEach((section) => {
    section.classList.toggle("is-active", section.id === "view-denied");
  });
  $$(".nav-item").forEach((button) => {
    button.classList.remove("is-active");
    button.removeAttribute("aria-current");
  });
}

function applyRoles() {
  $$(".nav-item").forEach((button) => {
    const roles = (button.dataset.roles || "").split(",").map((role) => role.trim()).filter(Boolean);
    button.hidden = roles.length > 0 && !roles.some((role) => state.roles.includes(role));
  });
  setView(firstAllowedView());
}

function setView(view, options = {}) {
  if (view === "denied" || !canView(view)) {
    showDenied();
    return;
  }

  state.view = view;
  $$(".view").forEach((section) => section.classList.toggle("is-active", section.id === `view-${view}`));
  $$(".nav-item").forEach((button) => {
    const active = button.dataset.view === view;
    button.classList.toggle("is-active", active);
    if (active) {
      button.setAttribute("aria-current", "page");
    } else {
      button.removeAttribute("aria-current");
    }
  });

  if (view === "clinician") {
    renderClinician();
  }
  if (view === "lab" && !options.keepLab) {
    loadLabOrders();
  }
  if (view === "billing") {
    loadBilling();
  }
  if (view === "pharmacy") {
    loadPharmacy();
  }
  if (view === "audit") {
    loadAudit();
  }
}

function renderPatientResults() {
  const list = $("#patient-results");
  const count = $("#search-count");
  const results = state.searchResults;

  count.textContent = results.length ? `${results.length} chart${results.length === 1 ? "" : "s"}` : "";

  if (!results.length) {
    const message = state.lastQuery
      ? `No charts match “${esc(state.lastQuery)}”.`
      : "No patients yet. Register the first chart.";
    list.innerHTML = `<p class="empty">${message}</p>`;
    return;
  }

  list.innerHTML = results.map((patient) => `
    <button class="result-row" type="button" data-patient-id="${esc(patient.id)}">
      <span>
        <span class="result-name">${esc(patientName(patient))}</span>
        <span class="result-meta">${esc(patientMeta(patient))}</span>
      </span>
      <span class="result-cell">${esc(patient.phone || "No phone")}</span>
      <span class="result-cell">Chart ${esc(shortId(patient.id))}</span>
      <span class="result-action">${syncBadge(patient) || "Open chart"}</span>
    </button>
  `).join("");
}

async function searchPatients(query) {
  state.lastQuery = query.trim();
  try {
    const remote = await api(`/api/patients?q=${encodeURIComponent(query.trim())}`);
    await offline.cachePatients(state.facilityId, remote);
    const cached = await offline.searchCachedPatients(state.facilityId, query);
    const pending = cached.filter((patient) => (
      (patient._pending || patient._failed) && !remote.some((item) => item.id === patient.id)
    ));
    state.searchResults = [...pending, ...remote];
  } catch (error) {
    if (!isOfflineError(error)) {
      throw error;
    }
    state.searchResults = await offline.searchCachedPatients(state.facilityId, query);
    showToast("Showing cached patients. Changes will sync when the API returns.", "warn");
  }
  renderPatientResults();
}

function renderChart() {
  renderBillingEncounterOptions();
  renderPharmacyEncounterOptions();
  const panel = $("#chart-panel");
  if (!state.patient) {
    panel.classList.add("is-hidden");
    return;
  }

  panel.classList.remove("is-hidden");
  $("#chart-name").textContent = patientName(state.patient);
  $("#chart-meta").textContent = patientMeta(state.patient);
  renderEncounterList();
}

function renderEncounterList() {
  const list = $("#encounter-list");
  if (!state.encounters.length) {
    list.innerHTML = '<p class="empty">No encounters yet. Start the first one.</p>';
    return;
  }

  list.innerHTML = state.encounters.map((encounter) => `
    <button class="result-row" type="button" data-encounter-id="${esc(encounter.id)}">
      <span>
        <span class="result-name">${esc(formatDateTime(encounter.createdAt))}</span>
        <span class="result-meta">${esc(encounter.clinicianId)}${encounter.notes ? ` · ${esc(encounter.notes)}` : ""}</span>
      </span>
      <span class="result-cell">${esc(vitalsSummary(encounter.vitals))}</span>
      <span class="result-action">${syncBadge(encounter)}${canView("clinician") ? "Order labs" : "View only"}</span>
    </button>
  `).join("");
}

function renderClinicianEncounters() {
  const list = $("#clinician-encounters");
  if (!state.encounters.length) {
    list.innerHTML = '<p class="empty">No encounters for this patient. Add one at the front desk.</p>';
    updateOrderForm();
    return;
  }

  list.innerHTML = state.encounters.map((encounter) => `
    <button class="result-row ${encounter.id === state.selectedEncounterId ? "is-selected" : ""}"
            type="button" data-clinician-encounter-id="${esc(encounter.id)}">
      <span>
        <span class="result-name">${esc(formatDateTime(encounter.createdAt))}</span>
        <span class="result-meta">${esc(encounter.clinicianId)} · ${encounter.notes ? esc(encounter.notes) : "No notes"}</span>
      </span>
      <span class="result-cell">${esc(vitalsSummary(encounter.vitals))}</span>
      <span class="result-action">${syncBadge(encounter)}${encounter.id === state.selectedEncounterId ? "Selected" : "Select"}</span>
    </button>
  `).join("");
  updateOrderForm();
}

function updateOrderForm() {
  const form = $("#order-form");
  if (!form) {
    return;
  }
  const encounter = state.encounters.find((item) => item.id === state.selectedEncounterId);
  const button = $('button[type="submit"]', form);
  const note = $("#order-encounter");
  button.disabled = !encounter;
  note.textContent = encounter
    ? `Encounter ${shortId(encounter.id)} · ${formatDateTime(encounter.createdAt)}`
    : "Select an encounter first.";
}

function renderClinician() {
  const empty = $("#clinician-empty");
  const workspace = $("#clinician-workspace");
  if (!state.patient) {
    empty.classList.remove("is-hidden");
    workspace.classList.add("is-hidden");
    return;
  }

  empty.classList.add("is-hidden");
  workspace.classList.remove("is-hidden");
  $("#clinician-patient-name").textContent = patientName(state.patient);
  $("#clinician-patient-meta").textContent = patientMeta(state.patient);
  renderClinicianEncounters();
}

async function selectPatient(patient) {
  state.patient = patient;
  state.selectedEncounterId = null;
  state.encounters = [];
  state.patientOrders = [];
  renderChart();
  renderClinician();

  if (patient._pending || patient._failed) {
    state.encounters = await offline.getCachedEncounters(state.facilityId, patient.id);
    if (state.encounters.length) {
      state.selectedEncounterId = state.encounters[0].id;
    }
    state.patientOrders = [];
    renderChart();
    renderClinician();
    renderPatientOrders();
    return;
  }

  try {
    state.encounters = await api(`/api/patients/${patient.id}/encounters`);
    await offline.cacheEncounters(state.facilityId, patient.id, state.encounters);
    if (state.encounters.length) {
      state.selectedEncounterId = state.encounters[0].id;
    }
    renderChart();
    renderClinician();
    await loadPatientOrders();
  } catch (error) {
    if (!isOfflineError(error)) {
      reportError(error);
      return;
    }
    state.encounters = await offline.getCachedEncounters(state.facilityId, patient.id);
    if (state.encounters.length) {
      state.selectedEncounterId = state.encounters[0].id;
    }
    state.patientOrders = [];
    renderChart();
    renderClinician();
    renderPatientOrders();
    showToast("Showing cached encounters. New changes will sync later.", "warn");
  }
}

function clearPatient() {
  state.patient = null;
  state.encounters = [];
  state.selectedEncounterId = null;
  state.patientOrders = [];
  renderChart();
  renderClinician();
}

async function loadPatientOrders() {
  if (!state.patient) {
    return;
  }
  try {
    state.patientOrders = await api(`/api/lab-orders?patientId=${state.patient.id}`);
  } catch (error) {
    if (!isOfflineError(error)) {
      throw error;
    }
    state.patientOrders = [];
  }
  renderPatientOrders();
}

function renderPatientOrders() {
  const list = $("#patient-orders");
  if (!state.patientOrders.length) {
    list.innerHTML = '<p class="empty">No lab orders yet. Send one from the form above.</p>';
    return;
  }

  list.innerHTML = state.patientOrders.map((order) => `
    <button class="result-row" type="button" data-patient-order-id="${esc(order.id)}">
      <span>
        <span class="result-name">${esc(testName(order.testCode))}</span>
        <span class="result-meta">Order ${esc(shortId(order.id))} · ${esc(formatDateTime(order.createdAt))}</span>
      </span>
      <span class="result-cell">${statusBadge(order.status)}</span>
      <span class="result-action">Open in lab</span>
    </button>
  `).join("");
}

function renderLabOrders() {
  const list = $("#lab-orders");
  const count = $("#lab-count");
  count.textContent = state.labOrders.length ? `${state.labOrders.length} order${state.labOrders.length === 1 ? "" : "s"}` : "";

  if (!state.labOrders.length) {
    list.innerHTML = '<p class="empty">No lab orders in this view.</p>';
    return;
  }

  list.innerHTML = state.labOrders.map((order) => `
    <button class="result-row ${state.selectedOrder && order.id === state.selectedOrder.id ? "is-selected" : ""}"
            type="button" data-lab-order-id="${esc(order.id)}">
      <span>
        <span class="result-name">${esc(testName(order.testCode))}</span>
        <span class="result-meta">Patient ${esc(shortId(order.patientId))} · ${esc(formatDateTime(order.createdAt))}</span>
      </span>
      <span class="result-cell">${statusBadge(order.status)}</span>
      <span class="result-action">${order.status === "ordered" ? "Collect" : order.status === "collected" ? "Record" : order.status === "resulted" && hasAnyRole("clinician", "admin") ? "Release" : "View"}</span>
    </button>
  `).join("");
}

async function loadLabOrders() {
  try {
    const query = state.labFilter ? `?status=${encodeURIComponent(state.labFilter)}` : "";
    state.labOrders = await api(`/api/lab-orders${query}`);
    renderLabOrders();
  } catch (error) {
    reportError(error);
  }
}

async function selectLabOrder(orderId) {
  state.selectedOrder = state.labOrders.find((order) => order.id === orderId) || null;
  state.amendments = [];
  renderLabOrders();
  renderLabDetail();
  if (state.selectedOrder && state.selectedOrder.status === "released") {
    await loadAmendments(state.selectedOrder.id);
  }
}

async function loadAmendments(orderId) {
  try {
    state.amendments = await api(`/api/lab-orders/${orderId}/amendments`);
  } catch (error) {
    state.amendments = [];
    reportError(error);
  }
  renderLabDetail();
}

function amendmentHistory() {
  if (!state.amendments.length) {
    return "";
  }
  return `
    <div class="panel-inset">
      <h3>Amendment history</h3>
      <div class="result-list">
        ${state.amendments.map((amendment) => `
          <div class="result-row">
            <span>
              <span class="result-name">${esc(amendment.previousValueText)} → ${esc(amendment.valueText)}${amendment.unit ? ` ${esc(amendment.unit)}` : ""}</span>
              <span class="result-meta">${esc(amendment.reason)}</span>
            </span>
            <span class="result-cell">${esc(amendment.amendedBy)}</span>
            <span class="result-cell">${esc(formatDateTime(amendment.createdAt))}</span>
          </div>
        `).join("")}
      </div>
    </div>
  `;
}

function amendmentForm(order) {
  if (order.status !== "released" || !order.result || !hasAnyRole("clinician", "admin")) {
    return "";
  }
  return `
    <form id="amend-form" class="form-stack panel-inset">
      <h3>Amend result</h3>
      <p class="form-note">The previous value stays in the amendment history and the audit chain.</p>
      <div class="form-grid">
        <label>Value
          <input name="valueText" required autocomplete="off" value="${esc(order.result.valueText)}">
        </label>
        <label>Unit
          <input name="unit" autocomplete="off" value="${esc(order.result.unit || "")}">
        </label>
      </div>
      <label>Reference range
        <input name="referenceRange" autocomplete="off" value="${esc(order.result.referenceRange || "")}">
      </label>
      <label>Reason
        <textarea name="reason" rows="2" required placeholder="Why is this result being amended?"></textarea>
      </label>
      <div class="form-actions">
        <button class="btn" type="submit">Amend result</button>
      </div>
    </form>
  `;
}

function resultBlock(result) {
  if (!result) {
    return "";
  }
  return `
    <div class="panel-inset result-block">
      <h3>Result</h3>
      <dl class="detail-list">
        <div><dt>Value</dt><dd>${esc(result.valueText)}${result.unit ? ` ${esc(result.unit)}` : ""}</dd></div>
        <div><dt>Reference</dt><dd>${esc(result.referenceRange || "—")}</dd></div>
        <div><dt>Released</dt><dd>${esc(result.releasedAt ? formatDateTime(result.releasedAt) : "Not released")}</dd></div>
        <div><dt>Released by</dt><dd>${esc(result.releasedBy || "—")}</dd></div>
        ${result.amendedAt ? `<div><dt>Amended</dt><dd>${esc(formatDateTime(result.amendedAt))} by ${esc(result.amendedBy || "—")}</dd></div>` : ""}
      </dl>
    </div>
  `;
}

function orderActions(order) {
  if (order.status === "ordered") {
    return `
      <div class="form-actions">
        <button class="btn" type="button" data-action="collect">Mark sample collected</button>
      </div>
    `;
  }
  if (order.status === "collected") {
    return `
      <form id="result-form" class="form-stack">
        <div class="form-grid">
          <label>Value
            <input name="valueText" required autocomplete="off" placeholder="e.g. 5.4">
          </label>
          <label>Unit
            <input name="unit" autocomplete="off" placeholder="e.g. mmol/L">
          </label>
        </div>
        <label>Reference range
          <input name="referenceRange" autocomplete="off" placeholder="e.g. 3.9–5.6">
        </label>
        <div class="form-actions">
          <button class="btn" type="submit">Record result</button>
        </div>
      </form>
    `;
  }
  if (order.status === "resulted") {
    if (!hasAnyRole("clinician", "admin")) {
      return '<p class="form-note">Waiting for a clinician to release this result.</p>';
    }
    return `
      <form id="release-form" class="form-stack">
        <label>Releasing clinician
          <input name="releasedBy" required autocomplete="off" value="${esc(actor())}">
        </label>
        <div class="form-actions">
          <button class="btn" type="submit">Release result</button>
        </div>
      </form>
    `;
  }
  if (order.status === "released") {
    return `<p class="form-note">Result released ${esc(order.result ? formatDateTime(order.result.releasedAt) : "")}.</p>`;
  }
  return '<p class="form-note">This order is closed.</p>';
}

function renderLabDetail() {
  const body = $("#lab-detail-body");
  const order = state.selectedOrder;
  if (!order) {
    body.innerHTML = '<p class="empty">Select an order to work on it.</p>';
    return;
  }

  body.innerHTML = `
    <h3 class="detail-title">${esc(testName(order.testCode))}</h3>
    <p class="detail-meta">Order ${esc(shortId(order.id))} · ${statusBadge(order.status)}</p>
    <dl class="detail-list">
      <div><dt>Patient</dt><dd>${esc(shortId(order.patientId))}</dd></div>
      <div><dt>Encounter</dt><dd>${esc(shortId(order.encounterId))}</dd></div>
      <div><dt>Ordered</dt><dd>${esc(formatDateTime(order.createdAt))}</dd></div>
    </dl>
    ${resultBlock(order.result)}
    <div id="order-actions">${orderActions(order)}</div>
    ${amendmentHistory()}
    ${amendmentForm(order)}
  `;

  const collect = $('[data-action="collect"]', body);
  if (collect) {
    collect.addEventListener("click", async () => {
      try {
        const updated = await api(`/api/lab-orders/${order.id}/collect`, { method: "POST" });
        state.selectedOrder = updated;
        state.labOrders = state.labOrders.map((item) => item.id === updated.id ? updated : item);
        renderLabOrders();
        renderLabDetail();
        showToast("Sample marked collected.");
      } catch (error) {
        reportError(error);
      }
    });
  }

  const resultForm = $("#result-form", body);
  if (resultForm) {
    resultForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const data = new FormData(resultForm);
      try {
        const updated = await api(`/api/lab-orders/${order.id}/results`, {
          method: "POST",
          body: JSON.stringify({
            valueText: data.get("valueText"),
            unit: data.get("unit"),
            referenceRange: data.get("referenceRange")
          })
        });
        state.selectedOrder = updated;
        state.labOrders = state.labOrders.map((item) => item.id === updated.id ? updated : item);
        renderLabOrders();
        renderLabDetail();
        showToast("Result recorded.");
      } catch (error) {
        reportError(error);
      }
    });
  }

  const releaseForm = $("#release-form", body);
  if (releaseForm) {
    releaseForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const data = new FormData(releaseForm);
      try {
        const updated = await api(`/api/lab-orders/${order.id}/release`, {
          method: "POST",
          body: JSON.stringify({ releasedBy: data.get("releasedBy") })
        });
        state.selectedOrder = updated;
        state.labOrders = state.labOrders.map((item) => item.id === updated.id ? updated : item);
        renderLabOrders();
        await loadAmendments(updated.id);
        showToast("Result released.");
      } catch (error) {
        reportError(error);
      }
    });
  }

  const amendForm = $("#amend-form", body);
  if (amendForm) {
    amendForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const data = new FormData(amendForm);
      try {
        const updated = await api(`/api/lab-orders/${order.id}/amendments`, {
          method: "POST",
          body: JSON.stringify({
            valueText: data.get("valueText"),
            unit: data.get("unit"),
            referenceRange: data.get("referenceRange"),
            reason: data.get("reason")
          })
        });
        state.selectedOrder = updated;
        state.labOrders = state.labOrders.map((item) => item.id === updated.id ? updated : item);
        renderLabOrders();
        await loadAmendments(updated.id);
        showToast("Result amended.");
      } catch (error) {
        reportError(error);
      }
    });
  }
}

function renderAudit(verification) {
  const status = $("#audit-status");
  const text = $("#audit-status-text");
  if (!verification) {
    status.dataset.state = "idle";
    text.textContent = "Not checked yet.";
    return;
  }
  if (verification.valid) {
    status.dataset.state = "valid";
    text.textContent = `Chain valid · ${verification.entries} ${verification.entries === 1 ? "entry" : "entries"}.`;
  } else {
    status.dataset.state = "broken";
    text.textContent = `Chain broken at entry ${verification.brokenAtId} · ${verification.entries} ${verification.entries === 1 ? "entry" : "entries"} checked.`;
  }
}

function renderAuditRows(entries) {
  const body = $("#audit-rows");
  if (!entries.length) {
    body.innerHTML = '<tr><td colspan="6" class="empty">No entries yet.</td></tr>';
    return;
  }

  body.innerHTML = entries.map((entry) => `
    <tr>
      <td class="mono">${esc(entry.id)}</td>
      <td>${esc(entry.action)}</td>
      <td><span class="mono">${esc(entry.entityType)}</span><br><span class="result-meta">${esc(shortId(entry.entityId))}</span></td>
      <td>${esc(entry.actorId)}</td>
      <td class="mono">${esc(formatDateTime(entry.createdAt))}</td>
      <td class="mono" title="${esc(entry.entryHash)}">${esc(entry.entryHash.slice(0, 12))}…</td>
    </tr>
  `).join("");
}

function renderBillingEncounterOptions() {
  const select = $("#billing-encounter");
  if (!select) {
    return;
  }
  const note = $("#billing-encounter-note");
  if (!state.patient || !state.encounters.length) {
    select.disabled = true;
    select.innerHTML = '<option value="">Select an encounter</option>';
    note.textContent = "Open a patient chart with an encounter at the front desk first.";
    return;
  }
  select.disabled = false;
  note.textContent = `${patientName(state.patient)} · ${state.encounters.length} encounter${state.encounters.length === 1 ? "" : "s"}`;
  select.innerHTML = state.encounters.map((encounter) => `
    <option value="${esc(encounter.id)}" ${encounter.id === state.selectedEncounterId ? "selected" : ""}>
      ${esc(formatDateTime(encounter.createdAt))} · ${esc(encounter.clinicianId)}
    </option>
  `).join("");
}

function renderBillingInvoices() {
  const list = $("#billing-invoices");
  const count = $("#billing-count");
  if (!list) {
    return;
  }
  count.textContent = state.billingInvoices.length ? `${state.billingInvoices.length}` : "";
  if (!state.billingInvoices.length) {
    list.innerHTML = '<p class="empty">No invoices yet.</p>';
    return;
  }
  list.innerHTML = state.billingInvoices.map((invoice) => `
    <button class="result-row ${state.selectedInvoice && state.selectedInvoice.id === invoice.id ? "is-selected" : ""}"
            type="button" data-invoice-id="${esc(invoice.id)}">
      <span>
        <span class="result-name">Invoice ${esc(shortId(invoice.id))}</span>
        <span class="result-meta">${esc(formatDateTime(invoice.createdAt))} · patient ${esc(shortId(invoice.patientId))}</span>
      </span>
      <span class="result-cell">${statusBadge(invoice.status)}</span>
      <span class="result-cell">${esc(money(invoice.totalCents))}</span>
      <span class="result-action">${syncBadge(invoice) || "Open"}</span>
    </button>
  `).join("");
}

function renderBillingDetail() {
  const body = $("#billing-detail-body");
  if (!body) {
    return;
  }
  const invoice = state.selectedInvoice;
  if (!invoice) {
    body.innerHTML = '<p class="empty">Select an invoice to add charges or collect payment.</p>';
    return;
  }
  const charges = invoice.charges || [];
  body.innerHTML = `
    <div class="panel-inset">
      <div class="panel-head">
        <div>
          <h3>Invoice ${esc(shortId(invoice.id))}</h3>
          <p class="result-meta">Encounter ${esc(shortId(invoice.encounterId))} · patient ${esc(shortId(invoice.patientId))}</p>
        </div>
        <span>${statusBadge(invoice.status)}${syncBadge(invoice)}</span>
      </div>
      <dl class="result-meta">
        <div><dt>Total</dt><dd>${esc(money(invoice.totalCents))}</dd></div>
        <div><dt>Created by</dt><dd>${esc(invoice.createdBy)} · ${esc(formatDateTime(invoice.createdAt))}</dd></div>
        ${invoice.paidAt ? `<div><dt>Paid</dt><dd>${esc(invoice.paidBy)} · ${esc(formatDateTime(invoice.paidAt))}</dd></div>` : ""}
      </dl>
    </div>
    <div class="panel-inset">
      <h3>Charges</h3>
      <div class="result-list">
        ${charges.length ? charges.map((charge) => `
          <div class="result-row">
            <span>
              <span class="result-name">${esc(charge.description)}</span>
              <span class="result-meta">${esc(charge.createdBy)} · ${esc(formatDateTime(charge.createdAt))}</span>
            </span>
            <span class="result-cell">${esc(money(charge.amountCents))}</span>
          </div>
        `).join("") : '<p class="empty">No charges on this invoice.</p>'}
      </div>
    </div>
    ${invoice.status === "open" ? `
      <form id="charge-form" class="panel-inset form-grid">
        <h3>Add a charge</h3>
        <label>Description
          <input name="description" required autocomplete="off" placeholder="Visit fee">
        </label>
        <label>Amount (USD)
          <input name="amount" type="number" min="0.01" step="0.01" required placeholder="120.00">
        </label>
        <div class="form-actions">
          <button class="btn" type="submit">Add charge</button>
        </div>
      </form>
      ${invoice.totalCents > 0 ? '<div class="panel-inset"><button id="pay-invoice" class="btn" type="button">Mark invoice paid</button></div>' : ""}
    ` : ""}
  `;
}

async function loadBilling() {
  renderBillingEncounterOptions();
  try {
    const remote = await api("/api/billing/invoices");
    await offline.cacheInvoices(state.facilityId, remote);
    const cached = await offline.listCachedInvoices(state.facilityId);
    const pending = cached.filter((invoice) => (
      (invoice._pending || invoice._failed) && !remote.some((item) => item.id === invoice.id)
    ));
    state.billingInvoices = [...pending, ...remote];
    renderBillingInvoices();
    if (state.selectedInvoice) {
      await selectInvoice(state.selectedInvoice.id);
    }
  } catch (error) {
    if (!isOfflineError(error)) {
      reportError(error);
      return;
    }
    state.billingInvoices = await offline.listCachedInvoices(state.facilityId);
    renderBillingInvoices();
    if (state.selectedInvoice) {
      const cached = await offline.getCachedInvoice(state.selectedInvoice.id);
      if (cached) {
        state.selectedInvoice = cached;
        renderBillingInvoices();
        renderBillingDetail();
      }
    }
    showToast("Showing cached invoices. New changes will sync later.", "warn");
  }
}

async function selectInvoice(invoiceId) {
  const alreadySelected = state.selectedInvoice && state.selectedInvoice.id === invoiceId;
  const keepChargeForm = alreadySelected && Boolean(
    $("#charge-form input[name=description]")?.value || $("#charge-form input[name=amount]")?.value
  );
  const local = await offline.getCachedInvoice(invoiceId);
  if (local && (local._pending || local._failed)) {
    state.selectedInvoice = local;
    renderBillingInvoices();
    if (!keepChargeForm) {
      renderBillingDetail();
    }
    return;
  }
  try {
    state.selectedInvoice = await api(`/api/billing/invoices/${invoiceId}`);
    await offline.cacheInvoice(state.facilityId, state.selectedInvoice);
    renderBillingInvoices();
    if (!keepChargeForm) {
      renderBillingDetail();
    }
  } catch (error) {
    if (!isOfflineError(error)) {
      reportError(error);
      return;
    }
    const cached = await offline.getCachedInvoice(invoiceId);
    if (!cached) {
      reportError(new Error("This invoice is not available offline."));
      return;
    }
    state.selectedInvoice = cached;
    renderBillingInvoices();
    renderBillingDetail();
    showToast("Showing the cached invoice.", "warn");
  }
}

function renderPharmacyEncounterOptions() {
  const select = $("#dispense-encounter");
  if (!select) {
    return;
  }
  const note = $("#dispense-encounter-note");
  if (!state.patient || !state.encounters.length) {
    select.disabled = true;
    select.innerHTML = '<option value="">Select an encounter</option>';
    note.textContent = "Open a patient chart with an encounter at the front desk first.";
    return;
  }
  select.disabled = false;
  note.textContent = `${patientName(state.patient)} · ${state.encounters.length} encounter${state.encounters.length === 1 ? "" : "s"}`;
  select.innerHTML = state.encounters.map((encounter) => `
    <option value="${esc(encounter.id)}" ${encounter.id === state.selectedEncounterId ? "selected" : ""}>
      ${esc(formatDateTime(encounter.createdAt))} · ${esc(encounter.clinicianId)}
    </option>
  `).join("");
}

function renderPharmacyItems() {
  const list = $("#pharmacy-items");
  const count = $("#pharmacy-count");
  if (!list) {
    return;
  }
  count.textContent = state.pharmacyItems.length ? `${state.pharmacyItems.length}` : "";
  if (!state.pharmacyItems.length) {
    list.innerHTML = '<p class="empty">No pharmacy items yet. Add the first item.</p>';
  } else {
    list.innerHTML = state.pharmacyItems.map((item) => `
      <div class="result-row">
        <span>
          <span class="result-name">${esc(item.name)}</span>
          <span class="result-meta">${esc(item.sku)} · ${esc(item.unit)} · reorder at ${esc(item.reorderLevel)}</span>
        </span>
        <span class="result-cell">${esc(item.stockQuantity)} in stock</span>
        <span class="result-cell">${item.stockQuantity <= item.reorderLevel ? "Low stock" : "OK"}</span>
      </div>
    `).join("");
  }

  const itemOptions = state.pharmacyItems.map((item) => `
    <option value="${esc(item.id)}">${esc(item.name)} (${esc(item.stockQuantity)} ${esc(item.unit)})</option>
  `).join("");
  const dispenseSelect = $("#dispense-item");
  const stockSelect = $("#stock-item");
  if (dispenseSelect) {
    dispenseSelect.innerHTML = '<option value="">Select an item</option>' + itemOptions;
  }
  if (stockSelect) {
    stockSelect.innerHTML = '<option value="">Select an item</option>' + itemOptions;
  }
}

function renderDispenses() {
  const list = $("#dispense-list");
  if (!list) {
    return;
  }
  if (!state.dispenses.length) {
    list.innerHTML = '<p class="empty">No dispenses yet.</p>';
    return;
  }
  list.innerHTML = state.dispenses.map((dispense) => {
    const item = state.pharmacyItems.find((candidate) => candidate.id === dispense.itemId);
    return `
      <div class="result-row">
        <span>
          <span class="result-name">${esc(item ? item.name : shortId(dispense.itemId))}</span>
          <span class="result-meta">patient ${esc(shortId(dispense.patientId))} · ${esc(formatDateTime(dispense.createdAt))}</span>
        </span>
        <span class="result-cell">${esc(dispense.quantity)}</span>
        <span class="result-cell">${esc(dispense.dispensedBy)}</span>
      </div>
    `;
  }).join("");
}

async function loadPharmacy() {
  renderPharmacyEncounterOptions();
  try {
    state.pharmacyItems = await api("/api/pharmacy/items");
    const query = state.patient ? `?patientId=${encodeURIComponent(state.patient.id)}` : "";
    state.dispenses = await api(`/api/pharmacy/dispenses${query}`);
    renderPharmacyItems();
    renderDispenses();
  } catch (error) {
    reportError(error);
  }
}

async function loadAudit() {
  try {
    const entries = await api("/api/audit?limit=100");
    renderAuditRows(entries);
    const verification = await api("/api/audit/verify");
    renderAudit(verification);
  } catch (error) {
    reportError(error);
  }
}

async function verifyAudit() {
  try {
    const verification = await api("/api/audit/verify");
    renderAudit(verification);
    showToast(verification.valid ? "Audit chain verified." : "Audit chain is broken.", verification.valid ? "ok" : "error");
  } catch (error) {
    reportError(error);
  }
}

async function openPatientOrder(orderId) {
  try {
    await loadLabOrders();
    const order = state.labOrders.find((item) => item.id === orderId);
    if (!order) {
      showToast("Order is not in the current lab queue.", "error");
      return;
    }
    state.selectedOrder = order;
    state.amendments = [];
    setView("lab", { keepLab: true });
    renderLabOrders();
    renderLabDetail();
    if (order.status === "released") {
      await loadAmendments(order.id);
    }
  } catch (error) {
    reportError(error);
  }
}

function bindEvents() {
  $$(".nav-item").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.view));
  });

  $$("[data-go]").forEach((button) => {
    button.addEventListener("click", () => setView(button.dataset.go));
  });

  $("#search-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
      await searchPatients($("#search-q").value);
    } catch (error) {
      reportError(error);
    }
  });

  $("#patient-results").addEventListener("click", (event) => {
    const row = event.target.closest("[data-patient-id]");
    if (!row) {
      return;
    }
    const patient = state.searchResults.find((item) => item.id === row.dataset.patientId);
    if (patient) {
      selectPatient(patient);
    }
  });

  $("#register-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const patient = {
      id: newId(),
      facilityId: state.facilityId,
      givenName: String(data.get("givenName") || "").trim(),
      familyName: String(data.get("familyName") || "").trim(),
      dateOfBirth: data.get("dateOfBirth"),
      phone: String(data.get("phone") || "").trim() || null,
      createdAt: new Date().toISOString(),
      _pending: true
    };
    try {
      await offline.cachePatient(patient);
      form.reset();
      $("#search-q").value = "";
      state.lastQuery = "";
      replaceById(state.searchResults, patient);
      state.patient = patient;
      state.encounters = [];
      state.selectedEncounterId = null;
      state.patientOrders = [];
      renderPatientResults();
      renderChart();
      renderClinician();
      await enqueueOperation({
        type: "patient.register",
        path: "/api/patients",
        targetId: patient.id,
        body: {
          id: patient.id,
          givenName: patient.givenName,
          familyName: patient.familyName,
          dateOfBirth: patient.dateOfBirth,
          phone: patient.phone
        }
      });
    } catch (error) {
      reportError(error);
    }
  });

  $("#encounter-list").addEventListener("click", (event) => {
    const row = event.target.closest("[data-encounter-id]");
    if (!row) {
      return;
    }
    if (!canView("clinician")) {
      showToast("Only clinicians can order labs from an encounter.", "error");
      return;
    }
    const encounter = state.encounters.find((item) => item.id === row.dataset.encounterId);
    if (encounter && (encounter._pending || encounter._failed)) {
      showToast("This encounter is still syncing. Lab orders need the server copy first.", "warn");
      return;
    }
    state.selectedEncounterId = row.dataset.encounterId;
    setView("clinician");
  });

  $("#encounter-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!state.patient) {
      return;
    }
    const form = event.currentTarget;
    const data = new FormData(form);
    const vitals = {};
    ["temperatureC", "heartRate", "bloodPressure"].forEach((key) => {
      const value = String(data.get(key) || "").trim();
      if (value) {
        vitals[key] = value;
      }
    });
    try {
      const encounter = {
        id: newId(),
        facilityId: state.facilityId,
        patientId: state.patient.id,
        clinicianId: String(data.get("clinicianId") || "").trim(),
        notes: String(data.get("notes") || "").trim() || null,
        vitals,
        createdAt: new Date().toISOString(),
        _pending: true
      };
      await offline.cacheEncounter(state.facilityId, encounter);
      state.encounters.unshift(encounter);
      state.selectedEncounterId = encounter.id;
      form.reset();
      $("#enc-clinician").value = actor();
      renderChart();
      renderClinician();
      await enqueueOperation({
        type: "encounter.create",
        path: "/api/encounters",
        targetId: encounter.id,
        body: {
          id: encounter.id,
          patientId: encounter.patientId,
          clinicianId: encounter.clinicianId,
          notes: encounter.notes,
          vitals: encounter.vitals
        }
      });
    } catch (error) {
      reportError(error);
    }
  });

  $("#chart-clear").addEventListener("click", clearPatient);

  $("#clinician-encounters").addEventListener("click", (event) => {
    const row = event.target.closest("[data-clinician-encounter-id]");
    if (!row) {
      return;
    }
    state.selectedEncounterId = row.dataset.clinicianEncounterId;
    renderClinicianEncounters();
  });

  $("#order-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!state.selectedEncounterId) {
      return;
    }
    const data = new FormData(event.currentTarget);
    try {
      const order = await api("/api/lab-orders", {
        method: "POST",
        body: JSON.stringify({
          encounterId: state.selectedEncounterId,
          testCode: data.get("testCode")
        })
      });
      await loadPatientOrders();
      showToast(`${testName(order.testCode)} sent to the lab.`);
    } catch (error) {
      reportError(error);
    }
  });

  $("#patient-orders").addEventListener("click", (event) => {
    const row = event.target.closest("[data-patient-order-id]");
    if (row) {
      openPatientOrder(row.dataset.patientOrderId);
    }
  });

  $$(".filter").forEach((button) => {
    button.addEventListener("click", () => {
      state.labFilter = button.dataset.status;
      $$(".filter").forEach((item) => item.classList.toggle("is-active", item === button));
      loadLabOrders();
    });
  });

  $("#lab-refresh").addEventListener("click", loadLabOrders);

  $("#lab-orders").addEventListener("click", (event) => {
    const row = event.target.closest("[data-lab-order-id]");
    if (row) {
      selectLabOrder(row.dataset.labOrderId);
    }
  });

  $("#billing-refresh").addEventListener("click", loadBilling);

  $("#billing-invoice-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const encounterId = String(data.get("encounterId") || "");
    const encounter = state.encounters.find((item) => item.id === encounterId);
    if (!encounter) {
      showToast("Open a patient chart and select an encounter first.", "error");
      return;
    }
    const invoice = {
      id: newId(),
      facilityId: state.facilityId,
      encounterId,
      patientId: encounter.patientId,
      status: "open",
      totalCents: 0,
      createdBy: actor(),
      createdAt: new Date().toISOString(),
      paidAt: null,
      paidBy: null,
      charges: [],
      _pending: true
    };
    try {
      await offline.cacheInvoice(state.facilityId, invoice);
      replaceById(state.billingInvoices, invoice);
      state.selectedInvoice = invoice;
      renderBillingInvoices();
      renderBillingDetail();
      await enqueueOperation({
        type: "billing.invoice.create",
        path: "/api/billing/invoices",
        targetId: invoice.id,
        body: { id: invoice.id, encounterId }
      });
    } catch (error) {
      reportError(error);
    }
  });

  $("#billing-invoices").addEventListener("click", (event) => {
    const row = event.target.closest("[data-invoice-id]");
    if (row) {
      selectInvoice(row.dataset.invoiceId);
    }
  });

  $("#billing-detail-body").addEventListener("submit", async (event) => {
    if (event.target.id !== "charge-form") {
      return;
    }
    event.preventDefault();
    const data = new FormData(event.target);
    const amount = Number(data.get("amount"));
    if (!Number.isFinite(amount) || amount <= 0) {
      showToast("Enter a charge amount greater than zero.", "error");
      return;
    }
    try {
      const invoice = state.selectedInvoice;
      const charge = {
        id: newId(),
        invoiceId: invoice.id,
        description: String(data.get("description") || "").trim(),
        amountCents: Math.round(amount * 100),
        createdBy: actor(),
        createdAt: new Date().toISOString(),
        _pending: true
      };
      const updated = {
        ...invoice,
        totalCents: Number(invoice.totalCents || 0) + charge.amountCents,
        charges: [...(invoice.charges || []), charge],
        _pending: true
      };
      await offline.cacheInvoice(state.facilityId, updated);
      replaceById(state.billingInvoices, updated);
      state.selectedInvoice = updated;
      renderBillingInvoices();
      renderBillingDetail();
      await enqueueOperation({
        type: "billing.charge.create",
        path: `/api/billing/invoices/${invoice.id}/charges`,
        targetId: charge.id,
        parentId: invoice.id,
        body: {
          id: charge.id,
          description: charge.description,
          amountCents: charge.amountCents
        }
      });
    } catch (error) {
      reportError(error);
    }
  });

  $("#billing-detail-body").addEventListener("click", async (event) => {
    if (event.target.id !== "pay-invoice") {
      return;
    }
    try {
      const invoice = state.selectedInvoice;
      const updated = {
        ...invoice,
        status: "paid",
        paidAt: new Date().toISOString(),
        paidBy: actor(),
        _pending: true
      };
      await offline.cacheInvoice(state.facilityId, updated);
      replaceById(state.billingInvoices, updated);
      state.selectedInvoice = updated;
      renderBillingInvoices();
      renderBillingDetail();
      await enqueueOperation({
        type: "billing.invoice.pay",
        path: `/api/billing/invoices/${invoice.id}/pay`,
        targetId: invoice.id,
        parentId: invoice.id
      });
    } catch (error) {
      reportError(error);
    }
  });

  $("#pharmacy-refresh").addEventListener("click", loadPharmacy);

  $("#pharmacy-item-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    try {
      await api("/api/pharmacy/items", {
        method: "POST",
        body: JSON.stringify({
          sku: data.get("sku"),
          name: data.get("name"),
          unit: data.get("unit"),
          stockQuantity: Number(data.get("stockQuantity") || 0),
          reorderLevel: Number(data.get("reorderLevel") || 0)
        })
      });
      form.reset();
      await loadPharmacy();
      showToast("Pharmacy item added.");
    } catch (error) {
      reportError(error);
    }
  });

  $("#stock-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const delta = Number(data.get("delta"));
    if (!Number.isInteger(delta) || delta === 0) {
      showToast("Enter a non-zero whole-number stock change.", "error");
      return;
    }
    try {
      await api(`/api/pharmacy/items/${data.get("itemId")}/stock`, {
        method: "POST",
        body: JSON.stringify({ delta })
      });
      form.reset();
      await loadPharmacy();
      showToast("Stock adjusted.");
    } catch (error) {
      reportError(error);
    }
  });

  $("#dispense-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const quantity = Number(data.get("quantity"));
    if (!Number.isInteger(quantity) || quantity <= 0) {
      showToast("Enter a whole-number quantity greater than zero.", "error");
      return;
    }
    try {
      await api("/api/pharmacy/dispenses", {
        method: "POST",
        body: JSON.stringify({
          itemId: data.get("itemId"),
          encounterId: data.get("encounterId"),
          quantity
        })
      });
      form.reset();
      await loadPharmacy();
      showToast("Dispense recorded.");
    } catch (error) {
      reportError(error);
    }
  });

  $("#audit-verify").addEventListener("click", verifyAudit);

  $("#denied-back").addEventListener("click", () => setView(firstAllowedView()));

  $("#sign-out").addEventListener("click", async () => {
    const queued = state.syncSummary.pending + state.syncSummary.syncing + state.syncSummary.failed;
    if (queued && !window.confirm(`${queued} queued change${queued === 1 ? "" : "s"} will be discarded. Sign out anyway?`)) {
      return;
    }
    try {
      await offline.clearOfflineData();
    } catch {
      // Sign out even if local cleanup fails.
    }
    keycloak.logout({ redirectUri: `${window.location.origin}/` });
  });
}

function init() {
  $("#session-user").textContent = state.user || "";
  $("#enc-clinician").value = actor();
  $("#today").textContent = new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long"
  }).format(new Date());

  bindEvents();
  applyRoles();
  checkHealth();
  searchPatients("").catch(reportError);
}

async function start() {
  keycloak = new Keycloak({
    url: KEYCLOAK_URL,
    realm: KEYCLOAK_REALM,
    clientId: KEYCLOAK_CLIENT_ID
  });

  const authenticated = await keycloak.init({
    onLoad: "login-required",
    pkceMethod: "S256",
    checkLoginIframe: false
  });
  if (!authenticated) {
    return;
  }

  state.user = keycloak.tokenParsed?.preferred_username || keycloak.subject || "user";
  state.roles = keycloak.tokenParsed?.realm_access?.roles || [];
  state.facilityId = keycloak.tokenParsed?.facility_id || null;

  const refreshSession = () => {
    keycloak.updateToken(REFRESH_MIN_VALIDITY_S).catch(() => {
      if (navigator.onLine && keycloak.isTokenExpired(0)) {
        keycloak.login();
      }
    });
  };

  keycloak.onTokenExpired = refreshSession;

  refreshTimer = window.setInterval(() => {
    if (keycloak.authenticated) {
      refreshSession();
    }
  }, REFRESH_INTERVAL_MS);

  try {
    await offline.openOfflineStore();
    await offline.resetStaleSyncing(state.facilityId);
    await refreshSyncSummary();
  } catch (error) {
    showToast(`Offline storage is unavailable: ${error.message}`, "error");
  }

  $("#auth-gate").classList.add("is-hidden");
  $("#session-user").textContent = state.user;
  init();
  flushOutbox();

  window.addEventListener("online", () => {
    checkHealth();
    flushOutbox({ force: true });
  });
  window.addEventListener("offline", () => {
    state.online = false;
    const connection = $("#connection");
    if (connection) {
      connection.dataset.state = "offline";
      connection.textContent = "API unavailable";
    }
    renderSyncStatus();
    showToast("You are offline. Front-desk changes will queue locally.", "warn");
  });
  $("#sync-now").addEventListener("click", () => flushOutbox({ force: true }));
  window.setInterval(() => {
    if (navigator.onLine && state.syncSummary.pending) {
      flushOutbox();
    }
  }, 15000);
}

start().catch((error) => {
  console.error(error);
  const gate = $(".auth-gate-text");
  if (gate) {
    gate.textContent = "Sign-in is unavailable. Check that Keycloak is running on port 8084.";
  }
  const connection = $("#connection");
  if (connection) {
    connection.dataset.state = "offline";
    connection.textContent = "Sign-in unavailable";
  }
});
