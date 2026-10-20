const FACILITY_ID = "00000000-0000-0000-0000-000000000001";

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
  cancelled: "Cancelled"
};

const state = {
  view: "front",
  patient: null,
  searchResults: [],
  lastQuery: "",
  encounters: [],
  selectedEncounterId: null,
  patientOrders: [],
  labOrders: [],
  selectedOrder: null,
  labFilter: "",
  toastTimer: null
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
  const input = $("#actor");
  return input.value.trim() || "demo-user";
}

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  headers.set("X-Facility-ID", FACILITY_ID);
  headers.set("X-Actor-ID", actor());

  const response = await fetch(path, { ...options, headers });
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
    throw new Error(message);
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
  try {
    const response = await fetch("/api/health");
    if (!response.ok) {
      throw new Error("unhealthy");
    }
    connection.dataset.state = "online";
    connection.textContent = "API ready";
  } catch {
    connection.dataset.state = "offline";
    connection.textContent = "API unavailable";
  }
}

function setView(view, options = {}) {
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
      <span class="result-action">Open chart</span>
    </button>
  `).join("");
}

async function searchPatients(query) {
  state.lastQuery = query.trim();
  state.searchResults = await api(`/api/patients?q=${encodeURIComponent(query.trim())}`);
  renderPatientResults();
}

function renderChart() {
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
      <span class="result-action">Order labs</span>
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
      <span class="result-action">${encounter.id === state.selectedEncounterId ? "Selected" : "Select"}</span>
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

  try {
    state.encounters = await api(`/api/patients/${patient.id}/encounters`);
    if (state.encounters.length) {
      state.selectedEncounterId = state.encounters[0].id;
    }
    renderChart();
    renderClinician();
    await loadPatientOrders();
  } catch (error) {
    reportError(error);
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
  state.patientOrders = await api(`/api/lab-orders?patientId=${state.patient.id}`);
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
      <span class="result-action">${order.status === "ordered" ? "Collect" : order.status === "collected" ? "Record" : order.status === "resulted" ? "Release" : "View"}</span>
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

function selectLabOrder(orderId) {
  state.selectedOrder = state.labOrders.find((order) => order.id === orderId) || null;
  renderLabOrders();
  renderLabDetail();
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
        renderLabDetail();
        showToast("Result released.");
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
    setView("lab", { keepLab: true });
    renderLabOrders();
    renderLabDetail();
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
    try {
      const patient = await api("/api/patients", {
        method: "POST",
        body: JSON.stringify({
          givenName: data.get("givenName"),
          familyName: data.get("familyName"),
          dateOfBirth: data.get("dateOfBirth"),
          phone: data.get("phone")
        })
      });
      form.reset();
      await searchPatients("");
      await selectPatient(patient);
      showToast(`${patientName(patient)} registered.`);
    } catch (error) {
      reportError(error);
    }
  });

  $("#encounter-list").addEventListener("click", (event) => {
    const row = event.target.closest("[data-encounter-id]");
    if (!row) {
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
      await api("/api/encounters", {
        method: "POST",
        body: JSON.stringify({
          patientId: state.patient.id,
          clinicianId: data.get("clinicianId"),
          notes: data.get("notes"),
          vitals
        })
      });
      form.reset();
      $("#enc-clinician").value = actor();
      state.encounters = await api(`/api/patients/${state.patient.id}/encounters`);
      state.selectedEncounterId = state.encounters.length ? state.encounters[0].id : null;
      renderChart();
      renderClinician();
      showToast("Encounter created.");
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

  $("#audit-verify").addEventListener("click", verifyAudit);

  $("#actor").addEventListener("change", () => {
    localStorage.setItem("meridian.actor", actor());
    if (state.view === "clinician") {
      updateOrderForm();
    }
  });
}

function init() {
  const storedActor = localStorage.getItem("meridian.actor");
  if (storedActor) {
    $("#actor").value = storedActor;
  }
  $("#enc-clinician").value = actor();
  $("#today").textContent = new Intl.DateTimeFormat("en-GB", {
    weekday: "long",
    day: "numeric",
    month: "long"
  }).format(new Date());

  bindEvents();
  checkHealth();
  searchPatients("").catch(reportError);
}

init();
