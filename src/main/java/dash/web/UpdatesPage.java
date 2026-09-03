package dash.web;

public class UpdatesPage {

    public static String render(String currentVersion, String latestVersion, boolean updateAvailable,
            boolean updatePrepared, boolean updaterEnabled) {
        return render(currentVersion, latestVersion, updateAvailable, updatePrepared, updaterEnabled, 60);
    }

    public static String render(String currentVersion, String latestVersion, boolean updateAvailable,
            boolean updatePrepared, boolean updaterEnabled, int updateIntervalMinutes) {
        String safeCurrent = escapeHtml(currentVersion == null || currentVersion.isBlank() ? "unknown" : currentVersion);
        String safeLatest = escapeHtml(latestVersion == null || latestVersion.isBlank() ? safeCurrent : latestVersion);

        String statusBadge;
        if (!updaterEnabled) {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-rose-500/20 text-rose-300 text-xs font-semibold\">Updater Disabled</span>";
        } else if (updatePrepared) {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-emerald-500/20 text-emerald-300 text-xs font-semibold\">Ready to Apply</span>";
        } else if (updateAvailable) {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-amber-500/20 text-amber-300 text-xs font-semibold\">Update Available</span>";
        } else {
            statusBadge = "<span class=\"px-3 py-1 rounded-full bg-slate-600/40 text-slate-300 text-xs font-semibold\">Up to Date</span>";
        }

        String primaryAction = "";
        if (updaterEnabled && updateAvailable && !updatePrepared) {
            primaryAction = "<button id=\"update-download-btn\" class=\"w-full sm:w-auto px-6 py-3 rounded-xl bg-primary/20 text-primary border border-primary/30 hover:bg-primary hover:text-black transition-all font-semibold\">Download Now</button>";
        }

        String restartAction = "";
        if (updatePrepared) {
            restartAction = "<button id=\"update-restart-btn\" class=\"w-full sm:w-auto px-7 py-3 rounded-xl bg-emerald-500 text-white hover:bg-emerald-400 transition-all font-bold shadow-lg\">Restart &amp; Apply</button>";
        }

        String pendingNote = updatePrepared
                ? "<p class=\"text-sm text-emerald-300 mt-2\">Update is staged and will be applied automatically when the server restarts. You can also click the button above to restart and apply it now.</p>"
                : "";

        String disabledHint = updaterEnabled
                ? ""
                : "<p class=\"text-sm text-rose-300\">Updater is disabled. Check the server console for the exact reason.</p>";

        String content = HtmlTemplate.statsHeader()
                + "<main class=\"p-4 sm:p-6 flex-1 w-full\">"
                + "<div class=\"flex flex-col gap-6 w-full\">"
                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6\">"
                + "<div class=\"flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3\">"
                + "<h2 class=\"text-xl font-bold text-white\">Dash Updates</h2>"
                + "<div class=\"flex flex-wrap items-center gap-2\">"
                + statusBadge
                + (updaterEnabled ? "<button id=\"scan-now-btn\" class=\"px-3 py-1.5 rounded-xl bg-slate-700/60 text-slate-300 border border-white/10 hover:bg-slate-600 transition-all text-sm font-semibold\">Scan for Updates Now</button>" : "")
                + "</div>"
                + "</div>"
                + "<p class=\"mt-2 text-slate-400 text-sm\">Manage ForgeDash updates directly from the panel.</p>"
                + "</div>"

                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6\">"
                + "<div class=\"grid grid-cols-1 md:grid-cols-2 gap-4\">"
                + "<div class=\"p-4 rounded-xl bg-white/5 border border-white/10\">"
                + "<p class=\"text-xs text-slate-400 uppercase tracking-wider\">Current Version</p>"
                + "<p class=\"mt-2 text-2xl font-bold text-white\">" + safeCurrent + "</p>"
                + "</div>"
                + "<div class=\"p-4 rounded-xl bg-white/5 border border-white/10\">"
                + "<p class=\"text-xs text-slate-400 uppercase tracking-wider\">Latest Version</p>"
                + "<p class=\"mt-2 text-2xl font-bold text-primary\">" + safeLatest + "</p>"
                + "</div>"
                + "</div>"
                + "<div class=\"mt-6 flex flex-col sm:flex-row gap-3\">"
                + primaryAction
                + restartAction
                + "</div>"
                + pendingNote
                + disabledHint
                + "</div>"

                + "<div class=\"rounded-3xl bg-glass-surface backdrop-blur-xl border border-glass-border p-6\">"
                + "<div class=\"flex items-center gap-3 mb-3\">"
                + "<span class=\"material-symbols-outlined text-primary\">schedule</span>"
                + "<h2 class=\"text-sm font-bold text-white uppercase tracking-wider\">Update Check Interval</h2>"
                + "</div>"
                + "<p class=\"text-xs text-slate-400 mb-4\">How often ForgeDash checks GitHub for a new release. Min 20 min, max 7 days (10080 min).</p>"
                + "<div class=\"flex items-center gap-3\">"
                + "<input type=\"number\" id=\"update-interval-input\" min=\"20\" max=\"10080\" value=\"" + updateIntervalMinutes + "\""
                + " class=\"w-28 bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-sm text-white font-mono focus:border-primary outline-none\">"
                + "<span class=\"text-slate-400 text-xs\">minutes</span>"
                + "</div>"
                + "<button id=\"save-update-interval\""
                + " class=\"mt-3 px-5 py-2 rounded-xl bg-primary/20 text-primary hover:bg-primary hover:text-black transition-all text-sm font-semibold\">Save Interval</button>"
                + "</div>"

                + "</div>"
                + "</main>"
                + HtmlTemplate.statsScript()
                + "<script>"
                + "document.getElementById('update-download-btn')?.addEventListener('click', async function(){"
                + "  const btn=this; btn.disabled=true;"
                + "  try {"
                + "    const res = await fetch('/api/update/download',{method:'POST'});"
                + "    const data = await res.json();"
                + "    if (data && data.success) { showToast('Update downloaded! It will be applied automatically when the server stops.', 'success'); if(window.dashNavigate){window.dashNavigate(location.pathname+location.search,'replace',undefined,true);} }"
                + "    else { showToast((data && data.error) ? data.error : 'Update download failed.', 'error'); btn.disabled=false; }"
                + "  } catch (e) { showToast('Update download failed.', 'error'); btn.disabled=false; }"
                + "});"
                + "document.getElementById('update-restart-btn')?.addEventListener('click', async function(){"
                + "  if (!confirm('Restart the server now to apply the update?')) return;"
                + "  const btn=this; btn.disabled=true;"
                + "  try {"
                + "    const res = await fetch('/api/update/restart',{method:'POST'});"
                + "    const data = await res.json();"
                + "    if (data && data.success) { showToast('Server is restarting. The update will be applied automatically.', 'success'); }"
                + "    else { showToast((data && data.error) ? data.error : 'Restart trigger failed.', 'error'); btn.disabled=false; }"
                + "  } catch (e) { showToast('Restart trigger failed.', 'error'); btn.disabled=false; }"
                + "});"
                + "document.getElementById('scan-now-btn')?.addEventListener('click', async function(){"
                + "  const btn=this; btn.disabled=true; btn.textContent='Scanning...';"
                + "  try {"
                + "    const res = await fetch('/updates/check',{method:'POST'});"
                + "    const data = await res.json();"
                + "    if (data && data.success) { showToast('Update check complete!', 'success'); if(window.dashNavigate){window.dashNavigate(location.pathname+location.search,'replace',undefined,true);}else{location.reload();} }"
                + "    else { showToast((data && data.error) ? data.error : 'Check failed.', 'error'); btn.disabled=false; btn.textContent='Scan for Updates Now'; }"
                + "  } catch (e) { showToast('Check failed.', 'error'); btn.disabled=false; btn.textContent='Scan for Updates Now'; }"
                + "});"
                + "document.getElementById('save-update-interval')?.addEventListener('click',async function(){"
                + "  const val=parseInt(document.getElementById('update-interval-input')?.value,10);"
                + "  if(isNaN(val)||val<20||val>10080){showToast('Interval must be 20-10080 minutes.','error');return;}"
                + "  const resp=await fetch('/api/settings/global',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'update_interval_minutes='+val});"
                + "  const data=await resp.json().catch(()=>({}));"
                + "  if(data.success)showToast('Update interval saved!','success');"
                + "  else showToast('Failed: '+(data.error||'unknown error'),'error');"
                + "});"
                + "</script>";

        return HtmlTemplate.page("Updates", "/updates", content);
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
