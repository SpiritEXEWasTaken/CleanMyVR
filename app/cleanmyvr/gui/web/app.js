// CleanMyVR GUI. All work happens in Python (window.pywebview.api.*); this file only renders.
const $ = (s) => document.querySelector(s);
const esc = (t) => String(t ?? "").replace(/[&<>"']/g, (c) => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
let api, users = [], conn = null, page = "connect";

function log(msg, cls) {
  const el = $("#log"), line = document.createElement("div");
  if (cls) line.className = cls;
  line.textContent = msg;
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}
window.onLog = (m) => log(m);
window.onTaskDone = (task, result, error) => {
  setBusy(false);
  if (error) log(`✖ ${task} failed: ${error}`, "err"); else log(`✔ ${task} finished`, "done");
  refresh();
};

function setBusy(b) { document.querySelectorAll("main button").forEach((x) => (x.disabled = b)); }

function show(p) {
  page = p;
  document.querySelectorAll("main > section").forEach((s) => s.classList.toggle("hidden", s.id !== "page-" + p));
  document.querySelectorAll("nav a").forEach((a) => a.classList.toggle("active", a.dataset.page === p));
  refresh();
}

async function run(task, args, confirmText) {
  if (confirmText && !confirm(confirmText)) return;
  setBusy(true);
  log(`▶ ${task}`);
  const r = await api.run(task, args || {});
  if (!r.started) { setBusy(false); log(r.error, "err"); }
}

function userChoice(container, name, onchange) {
  const el = $(container);
  el.innerHTML = users.map((u, i) =>
    `<label><input type="radio" name="${name}" value="${u.id}" ${u.current ? "checked" : (i === 0 ? "checked" : "")}>${esc(u.name)} <span class="muted small">profile ${u.id}${u.current ? " · active" : ""}</span></label>`).join("");
  el.querySelectorAll("input").forEach((i) => (i.onchange = onchange));
}
const picked = (name) => document.querySelector(`input[name="${name}"]:checked`)?.value;

// ------------------------------------------------------------------ connect
async function doConnect(download) {
  $("#connect-msg").innerHTML = '<span class="muted">Looking for your headset…</span>';
  const r = await api.connect(!!download, $("#device-pick").value || null);
  $("#btn-download").classList.toggle("hidden", r.status !== "no_adb");
  const pick = $("#device-pick");
  pick.classList.toggle("hidden", r.status !== "many");
  if (r.status === "many") pick.innerHTML = r.devices.map((d) => `<option value="${esc(d.serial)}">${esc(d.model || d.serial)} (${esc(d.state)})</option>`).join("");
  $("#connect-msg").className = "card banner" + (r.status === "ok" ? " good" : "");
  $("#connect-msg").textContent = r.message;
  if (r.status !== "ok") return;
  conn = r;
  users = r.users;
  $("#devname").innerHTML = `<b>${esc(r.device.name)}</b><br>${esc(r.device.build)}`;
  show("overview");
}

// ------------------------------------------------------------------ pages
async function refresh() {
  if (!conn || page === "connect") return;
  if (page === "overview") return overview();
  if (page === "debloat") return debloatPage();
  if (page === "backup") return backupPage();
  if (page === "storage") return storagePage();
  if (page === "environment") return envPage();
  if (page === "launcher") return launcherPage();
  if (page === "restore") return restorePage();
  if (page === "extras") return extrasPage();
}

async function overview() {
  const d = conn.device;
  $("#ov-device").textContent = `${d.name} · ${d.model} · build ${d.build}` + (d.tested ? (d.build_tested ? " · tested" : " · newer build than tested") : " · not tested yet");
  $("#ov-notes").innerHTML = (!d.tested ? `<div class="card banner bad">This headset has no tested profile, so debloat is disabled. Backups, storage and checks still work. <span class="muted">See docs/ADDING_DEVICES.md to add support.</span></div>` : "");
  const rc = await api.recheck();
  $("#ov-items").innerHTML = rc.items.map((it) =>
    `<div class="card row"><span class="pill ${it.status}">${{ok: "OK", drift: "CHANGED", todo: "TO DO", problem: "PROBLEM", gone: "GONE"}[it.status] || it.status}</span>
     <b style="min-width:110px">${esc(it.area)}</b><span class="grow">${it.user !== undefined ? "profile " + it.user + ": " : ""}${esc(it.text)}</span></div>`).join("");
  $("#btn-fix").classList.toggle("hidden", !rc.items.some((i) => i.status === "drift"));
  $("#ov-history").innerHTML = rc.history.length ? rc.history.slice().reverse().map((h) =>
    `${esc(h.at.replace("T", " ").slice(0, 16))} · ${esc(h.action)}${h.user !== undefined ? " (profile " + h.user + ")" : ""}`).join("<br>") : "Nothing yet.";
}

async function debloatPage() {
  if (!$("#db-users").children.length) userChoice("#db-users", "dbuser", debloatPreview);
  $("#db-untested").classList.toggle("hidden", conn.device.tested);
  $("#db-untested").textContent = `Debloat is disabled for ${conn.device.name}: its profile hasn't been tested yet.`;
  $("#btn-debloat").disabled = !conn.device.tested;
  debloatPreview();
}
async function debloatPreview() {
  const plan = await api.plan_debloat(picked("level"), picked("dbuser"));
  $("#db-plan").innerHTML = plan.map((p) => p.status === "remove"
    ? `<div><label class="grow" style="display:flex;gap:10px;cursor:pointer"><input type="checkbox" class="db-pick" value="${esc(p.pkg)}" ${p.keep ? "" : "checked"}>
         <code>${esc(p.pkg)}</code><span class="grow muted">${esc(p.why)}</span></label></div>`
    : `<div><code>${esc(p.pkg)}</code><span class="grow muted">${esc(p.why)}</span><span class="pill ok">${p.status === "refused" ? "protected" : "not installed"}</span></div>`).join("");
  document.querySelectorAll(".db-pick").forEach((c) => (c.onchange = debloatCount));
  debloatCount();
}
function debloatCount() {
  const all = [...document.querySelectorAll(".db-pick")], on = all.filter((c) => c.checked).length;
  $("#db-count").textContent = all.length ? `Will remove ${on} of ${all.length} app(s) · untick anything you want to keep` : "Nothing left to remove at this level";
  $("#btn-debloat").disabled = !conn.device.tested || !on;
}

async function backupPage() {
  if (!$("#bk-users").children.length) userChoice("#bk-users", "bkuser", async () => ($("#bk-dest").value = await api.default_backup_dir(picked("bkuser"))));
  if (!$("#bk-dest").value) $("#bk-dest").value = await api.default_backup_dir(picked("bkuser"));
}

async function storagePage() {
  const s = await api.storage();
  $("#st-free").textContent = `${s.storage.free || "?"} free of ${s.storage.size || "?"}`;
  $("#st-apps").innerHTML = s.biggest_apps.length ? s.biggest_apps.map((a) => `${(a.bytes / 1e9).toFixed(2)} GB · ${esc(a.pkg)}`).join("<br>") : esc(s.note || "—");
  const caps = Object.entries(s.captures);
  $("#st-caps").innerHTML = caps.map(([k, v]) => `${esc(k)}: ${(v / 1e6).toFixed(1)} MB`).join("<br>");
}

async function envPage() {
  const e = await api.environment();
  const box = $("#env-box");
  if (!e.available) { box.className = "card banner"; box.textContent = "Couldn't read the home environment. Put the headset on so its home wakes up, then check again."; return; }
  box.className = "card banner " + (e.problem ? "bad" : "good");
  box.innerHTML = `<b>${e.problem ? esc(e.problem) : "Home environment is fine."}</b><br>
    <span class="muted small">Selected: ${esc(e.selected || "(default)")}<br>Loaded: ${esc(e.loaded || "(passthrough)")}</span>` +
    (e.problem ? `<p class="small">Fix: back up, then factory reset (clears the stuck selection), or the README's stand-in environment trick (needs the Android SDK).</p>` : "");
}

async function launcherPage() {
  $("#ln-box").innerHTML = conn.launcher_apk ? "Ready to install. Takes over the Library button; undo with Remove."
    : `<span class="muted">launcher.apk isn't bundled with this copy. Build it with launcher/build.sh or use a release download.</span>`;
  $("#btn-ln-install").disabled = !conn.launcher_apk;
}

async function restorePage() {
  if (!$("#rs-users").children.length) userChoice("#rs-users", "rsuser", restorePage);
  const list = await api.removed(picked("rsuser"));
  $("#rs-list").textContent = list.length ? list.join(", ") : "Nothing recorded as removed on this profile.";
  $("#btn-rs-apps").disabled = !list.length;
}

async function extrasPage() {
  const s = await api.extras_status();
  $("#ex-box").innerHTML = `<b>microG: ${s.microg ? "installed" : "not installed"} · Aurora Store: ${s.aurora ? "installed" : "not installed"}</b>
    <p class="muted small">${esc(s.note)}</p>`;
  $("#btn-ex-install").disabled = s.microg && s.aurora;
  $("#btn-ex-remove").disabled = !s.microg && !s.aurora;
}

// ------------------------------------------------------------------ updates
async function checkUpdate() {
  const u = await api.check_update();
  const bar = $("#update-bar");
  if (!u.available) return bar.classList.add("hidden");
  const how = {exe: "Update now", git: "Update now (git pull)", manual: "Download"}[u.kind];
  bar.innerHTML = `<div class="row"><span class="grow"><b>CleanMyVR ${esc(u.latest)} is available</b>
    <span class="muted small">(you have ${esc(u.current)})</span></span>
    <a href="#" id="upd-notes">What's new</a><button class="primary" id="btn-update">${how}</button></div>
    <pre id="upd-text" class="small muted hidden" style="white-space:pre-wrap">${esc(u.notes)}</pre>`;
  bar.classList.remove("hidden");
  $("#upd-notes").onclick = (e) => { e.preventDefault(); $("#upd-text").classList.toggle("hidden"); };
  $("#btn-update").onclick = () => { $("#btn-update").disabled = true; log("▶ update"); api.apply_update(); };
}
window.onUpdateDone = (r, err) => {
  if (err) { log(`✖ update failed: ${err}`, "err"); $("#btn-update").disabled = false; return; }
  log(r.message, r.done ? "done" : undefined);
  $("#update-bar").innerHTML = `<b>${esc(r.message)}</b>`;
};

// ------------------------------------------------------------------ wiring
function wire() {
  document.querySelectorAll("nav a").forEach((a) => (a.onclick = (e) => { e.preventDefault(); if (conn) show(a.dataset.page); }));
  $("#btn-connect").onclick = () => doConnect(false);
  $("#btn-download").onclick = () => doConnect(true);
  $("#btn-recheck").onclick = overview;
  $("#btn-fix").onclick = () => run("fix_drift", {}, "Re-remove apps that came back, re-apply update blocking and restart the launcher if it stopped?");
  document.querySelectorAll('input[name="level"]').forEach((i) => (i.onchange = debloatPreview));
  $("#btn-debloat").onclick = () => {
    const keep = [...document.querySelectorAll(".db-pick")].filter((c) => !c.checked).map((c) => c.value);
    run("debloat", {level: picked("level"), user: picked("dbuser"), keep}, "Remove the ticked apps? Unticked ones are kept and remembered. You can restore everything later.");
  };
  $("#btn-bk-pick").onclick = async () => { const f = await api.choose_folder(); if (f) $("#bk-dest").value = f; };
  $("#btn-backup").onclick = () => run("backup", {user: picked("bkuser"), dest: $("#bk-dest").value, media: $("#bk-media").checked, saves: $("#bk-saves").checked});
  $("#btn-caches").onclick = () => run("clear_caches");
  $("#btn-delcaps").onclick = () => {
    if (prompt("This permanently deletes every screenshot, recording, photo and video on the active profile. Back up first!\nType DELETE to continue.") === "DELETE") run("delete_captures");
  };
  $("#btn-env").onclick = envPage;
  $("#btn-ln-install").onclick = () => run("install_launcher");
  $("#btn-ln-repair").onclick = () => run("repair_launcher");
  $("#btn-ln-remove").onclick = () => run("uninstall_launcher", {}, "Remove the launcher and give the Library back to Meta?");
  $("#btn-rs-apps").onclick = () => run("restore", {user: picked("rsuser")}, "Reinstall the apps listed?");
  $("#btn-rs-pick").onclick = async () => { const f = await api.choose_folder(); if (f) $("#rs-src").value = f; };
  $("#btn-ex-install").onclick = () => run("install_play_apps", {}, "Install microG Services, microG Companion and Aurora Store? (experimental; removable)");
  $("#btn-ex-remove").onclick = () => run("remove_play_apps", {}, "Remove microG and Aurora Store?");
  $("#btn-rs-backup").onclick = () => run("restore_backup", {src: $("#rs-src").value, user: picked("rsuser")}, "Copy this backup onto the picked profile?");
}

window.addEventListener("pywebviewready", async () => {
  api = window.pywebview.api;
  $("#ver").textContent = "v" + (await api.version());
  wire();
  doConnect(false);
  checkUpdate();
});
