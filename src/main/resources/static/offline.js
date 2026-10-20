const DB_NAME = "meridian-offline";
const DB_VERSION = 1;
const OUTBOX = "outbox";
const PATIENTS = "patients";
const ENCOUNTERS = "encounters";
const INVOICES = "invoices";
const META = "meta";

let dbPromise = null;

function openDb() {
  if (!("indexedDB" in window)) {
    return Promise.reject(new Error("This browser does not support offline storage."));
  }
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const request = window.indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(OUTBOX)) {
          const store = db.createObjectStore(OUTBOX, { keyPath: "operationId" });
          store.createIndex("createdAt", "createdAt");
          store.createIndex("status", "status");
        }
        if (!db.objectStoreNames.contains(PATIENTS)) {
          const store = db.createObjectStore(PATIENTS, { keyPath: "id" });
          store.createIndex("facilityId", "facilityId");
        }
        if (!db.objectStoreNames.contains(ENCOUNTERS)) {
          const store = db.createObjectStore(ENCOUNTERS, { keyPath: "id" });
          store.createIndex("facilityId", "facilityId");
          store.createIndex("patientId", "patientId");
        }
        if (!db.objectStoreNames.contains(INVOICES)) {
          const store = db.createObjectStore(INVOICES, { keyPath: "id" });
          store.createIndex("facilityId", "facilityId");
          store.createIndex("patientId", "patientId");
        }
        if (!db.objectStoreNames.contains(META)) {
          db.createObjectStore(META, { keyPath: "key" });
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  return dbPromise;
}

async function readAll(storeName) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = db.transaction(storeName, "readonly").objectStore(storeName).getAll();
    request.onsuccess = () => resolve(request.result || []);
    request.onerror = () => reject(request.error);
  });
}

async function readOne(storeName, key) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const request = db.transaction(storeName, "readonly").objectStore(storeName).get(key);
    request.onsuccess = () => resolve(request.result || null);
    request.onerror = () => reject(request.error);
  });
}

async function put(storeName, value) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(storeName, "readwrite");
    transaction.objectStore(storeName).put(value);
    transaction.oncomplete = () => resolve(value);
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

async function remove(storeName, key) {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(storeName, "readwrite");
    transaction.objectStore(storeName).delete(key);
    transaction.oncomplete = () => resolve();
    transaction.onerror = () => reject(transaction.error);
    transaction.onabort = () => reject(transaction.error);
  });
}

export async function openOfflineStore() {
  await openDb();
}

export async function enqueueOperation(entry) {
  return put(OUTBOX, entry);
}

export async function listOutbox(facilityId) {
  const entries = await readAll(OUTBOX);
  return entries
    .filter((entry) => entry.facilityId === facilityId)
    .sort((left, right) => left.createdAt.localeCompare(right.createdAt));
}

export async function updateOutbox(operationId, patch) {
  const existing = await readOne(OUTBOX, operationId);
  if (!existing) {
    return null;
  }
  const updated = { ...existing, ...patch };
  await put(OUTBOX, updated);
  return updated;
}

export async function removeOutbox(operationId) {
  return remove(OUTBOX, operationId);
}

export async function resetStaleSyncing(facilityId) {
  const entries = await listOutbox(facilityId);
  for (const entry of entries) {
    if (entry.status === "syncing") {
      await updateOutbox(entry.operationId, { status: "pending" });
    }
  }
}

export async function outboxSummary(facilityId) {
  const entries = await listOutbox(facilityId);
  return entries.reduce((summary, entry) => {
    summary[entry.status] = (summary[entry.status] || 0) + 1;
    return summary;
  }, { pending: 0, syncing: 0, failed: 0 });
}

export async function cachePatient(patient) {
  if (!patient || !patient.id) {
    return;
  }
  await put(PATIENTS, { ...patient, facilityId: patient.facilityId, cachedAt: new Date().toISOString() });
}

export async function cachePatients(facilityId, patients) {
  for (const patient of patients || []) {
    await put(PATIENTS, { ...patient, facilityId, cachedAt: new Date().toISOString() });
  }
}

export async function searchCachedPatients(facilityId, query) {
  const term = String(query || "").trim().toLowerCase();
  const patients = await readAll(PATIENTS);
  return patients
    .filter((patient) => patient.facilityId === facilityId)
    .filter((patient) => {
      if (!term) {
        return true;
      }
      const name = `${patient.givenName || ""} ${patient.familyName || ""}`.toLowerCase();
      return name.includes(term);
    })
    .sort((left, right) => `${left.familyName} ${left.givenName}`.localeCompare(`${right.familyName} ${right.givenName}`))
    .slice(0, 100);
}

export async function getCachedPatient(patientId) {
  return readOne(PATIENTS, patientId);
}

export async function cacheEncounter(facilityId, encounter) {
  if (!encounter || !encounter.id) {
    return;
  }
  await put(ENCOUNTERS, { ...encounter, facilityId, cachedAt: new Date().toISOString() });
}

export async function cacheEncounters(facilityId, patientId, encounters) {
  for (const encounter of encounters || []) {
    await put(ENCOUNTERS, { ...encounter, facilityId, patientId, cachedAt: new Date().toISOString() });
  }
}

export async function getCachedEncounters(facilityId, patientId) {
  const encounters = await readAll(ENCOUNTERS);
  return encounters
    .filter((encounter) => encounter.facilityId === facilityId && encounter.patientId === patientId)
    .sort((left, right) => String(right.createdAt).localeCompare(String(left.createdAt)));
}

export async function cacheInvoice(facilityId, invoice) {
  if (!invoice || !invoice.id) {
    return;
  }
  await put(INVOICES, { ...invoice, facilityId, cachedAt: new Date().toISOString() });
}

export async function cacheInvoices(facilityId, invoices) {
  for (const invoice of invoices || []) {
    await put(INVOICES, { ...invoice, facilityId, cachedAt: new Date().toISOString() });
  }
}

export async function getCachedInvoice(invoiceId) {
  return readOne(INVOICES, invoiceId);
}

export async function listCachedInvoices(facilityId, patientId) {
  const invoices = await readAll(INVOICES);
  return invoices
    .filter((invoice) => invoice.facilityId === facilityId)
    .filter((invoice) => !patientId || invoice.patientId === patientId)
    .sort((left, right) => String(right.createdAt).localeCompare(String(left.createdAt)));
}

export async function setMeta(key, value) {
  await put(META, { key, value });
}

export async function getMeta(key) {
  const entry = await readOne(META, key);
  return entry ? entry.value : null;
}

export async function clearOfflineData() {
  if (dbPromise) {
    try {
      (await dbPromise).close();
    } catch {
      // Ignore close failures and try the delete below.
    }
  }
  dbPromise = null;
  await new Promise((resolve, reject) => {
    const request = window.indexedDB.deleteDatabase(DB_NAME);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error);
    request.onblocked = () => resolve();
  });
}
